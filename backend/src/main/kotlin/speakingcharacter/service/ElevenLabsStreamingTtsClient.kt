/** Реализует ElevenLabs WebSocket TTS для PCM16, совместимого с Simli. */
package speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import speakingcharacter.config.AppConfig

/** Безопасная ошибка потокового TTS, не содержащая текста реплики или ключа. */
class ElevenLabsException(message: String) : RuntimeException(message)

/**
 * Один WebSocket ElevenLabs на один ход диалога.
 * Он принимает Gemini-дельты, отправляет word-safe chunks и возвращает PCM16 16 kHz.
 */
class ElevenLabsStreamingTtsClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) : StreamingTtsClient {
    private val logger = LoggerFactory.getLogger(ElevenLabsStreamingTtsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Запускает TTS-поток с PCM16, alignment и счётчиками использования. */
    override fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame> = channelFlow {
        val apiKey = requireNotNull(config.elevenLabsApiKey) { "ELEVENLABS_API_KEY is not configured" }
        val voiceId = requireNotNull(config.elevenLabsVoiceId) { "ELEVENLABS_VOICE_ID is not configured" }
        val url = "wss://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream-input" +
            "?model_id=${config.elevenLabsModel}&output_format=pcm_16000&sync_alignment=true"
        var sentCharacters = 0
        var pcmBytes = 0L
        var firstAudioLogged = false
        val startedAt = System.nanoTime()

        try {
            httpClient.webSocket(urlString = url) {
                send(Frame.Text(json.encodeToString(JsonObject.serializer(), initialMessage(apiKey))))
                val sender = launch {
                    val buffer = WordSafeTextBuffer(config.streamMinChars)
                    textDeltas.collect { delta ->
                        buffer.append(delta).forEach { chunk ->
                            sentCharacters += chunk.length
                            send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage(chunk, false))))
                        }
                    }
                    val tail = buffer.finish()
                    if (tail.isNotEmpty()) {
                        sentCharacters += tail.length
                        send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage(tail, true))))
                    } else {
                        send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage(" ", true))))
                    }
                    send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage("", false))))
                }
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val payload = parseAudio(frame.readText())
                    if (payload.pcm.isNotEmpty()) {
                        if (!firstAudioLogged) {
                            firstAudioLogged = true
                            logger.info("tts_first_audio durationMs={}", (System.nanoTime() - startedAt) / 1_000_000)
                        }
                        pcmBytes += payload.pcm.size
                        this@channelFlow.send(payload)
                    }
                    if (payload.isFinal) break
                }
                sender.join()
                outgoing.close()
            }
        } catch (exception: ElevenLabsException) {
            throw exception
        } catch (_: Exception) {
            throw ElevenLabsException("ElevenLabs streaming TTS failed")
        } finally {
            logger.info(
                "tts_stream_completed durationMs={} characters={} pcmBytes={} firstAudio={}",
                (System.nanoTime() - startedAt) / 1_000_000,
                sentCharacters,
                pcmBytes,
                firstAudioLogged,
            )
        }
    }.buffer(capacity = 1)

    /** Формирует стартовое сообщение с моделью и расписанием ранней генерации аудио. */
    private fun initialMessage(apiKey: String): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(" "))
        put("xi_api_key", JsonPrimitive(apiKey))
        put("generation_config", buildJsonObject {
            put("chunk_length_schedule", buildJsonArray {
                add(JsonPrimitive(50))
                add(JsonPrimitive(80))
                add(JsonPrimitive(120))
            })
        })
    }

    /** Формирует очередную text-реплику, при необходимости принудительно сбрасывая буфер TTS. */
    private fun textMessage(text: String, flush: Boolean): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(text))
        if (flush) put("flush", JsonPrimitive(true))
    }

    /** Декодирует одно сообщение ElevenLabs, включая документированный character alignment. */
    internal fun parseAudio(rawMessage: String): TtsAudioFrame = try {
        val objectMessage = json.parseToJsonElement(rawMessage).jsonObject
        val encoded = (objectMessage["audio"] as? JsonPrimitive)?.contentOrNull
        val pcm = encoded?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
        val isFinal = (objectMessage["is_final"] as? JsonPrimitive)?.booleanOrNull
            ?: (objectMessage["isFinal"] as? JsonPrimitive)?.booleanOrNull
            ?: false
        TtsAudioFrame(pcm, parseAlignment(objectMessage["alignment"] as? JsonObject), isFinal)
    } catch (_: Exception) {
        throw ElevenLabsException("ElevenLabs returned an invalid audio frame")
    }

    /** Возвращает alignment только при полном и корректном наборе массивов от провайдера. */
    private fun parseAlignment(rawAlignment: JsonObject?): TtsAlignment? {
        val chars = rawAlignment?.get("chars")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        val starts = rawAlignment["char_start_times_ms"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return null
        val durations = rawAlignment["char_durations_ms"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return null
        return TtsAlignment(chars, starts, durations).takeIf {
            it.chars.isNotEmpty() && it.chars.size == it.charStartTimesMs.size && it.chars.size == it.charDurationsMs.size
        }
    }
}
