/** Регистрирует Simli token endpoint и WebSocket pipeline Gemini → TTS → PCM. */
package speakingcharacter.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import speakingcharacter.config.AppConfig
import speakingcharacter.config.AvatarProvider
import speakingcharacter.service.ConversationService
import speakingcharacter.service.ElevenLabsException
import speakingcharacter.service.GeminiException
import speakingcharacter.service.SimliException
import speakingcharacter.service.SimliSessionTokenClient
import speakingcharacter.service.StreamingTtsClient
import speakingcharacter.service.SessionFinishedException
import speakingcharacter.service.SessionNotFoundException

private val streamingRoutesLogger = LoggerFactory.getLogger("StreamingRoutes")
private val streamingJson = Json { ignoreUnknownKeys = true }

/** Регистрирует endpoints только для потокового Simli режима, не меняя D-ID API. */
fun Application.registerStreamingRoutes(
    config: AppConfig,
    conversationService: ConversationService,
    ttsClient: StreamingTtsClient,
    simliSessionTokenClient: SimliSessionTokenClient,
) {
    routing {
        post("/api/avatar/simli/session") {
            if (config.avatarProvider != AvatarProvider.SIMLI) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Simli avatar provider is disabled"))
                return@post
            }
            try {
                call.respond(SimliSessionResponse(simliSessionTokenClient.createSessionToken(), config.simliTransport))
            } catch (exception: SimliException) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(exception.message ?: "Simli session request failed"))
            }
        }
        webSocket("/api/chat/stream") {
            if (config.avatarProvider != AvatarProvider.SIMLI) {
                sendEvent(ChatStreamEvent("error", error = "Simli avatar provider is disabled"))
                outgoing.close()
                return@webSocket
            }
            val start = receiveStart() ?: return@webSocket
            val work = launchStreamingTurn(conversationService, ttsClient, start)
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val command = streamingJson.decodeFromString<ChatStreamRequest>(frame.readText())
                    if (command.type == "cancel") {
                        work.cancel(CancellationException("Browser cancelled the streaming turn"))
                        sendEvent(ChatStreamEvent("done"))
                outgoing.close()
                        break
                    }
                }
            } finally {
                if (!work.isCompleted) work.cancel()
                work.join()
            }
        }
    }
}

/** Читает и валидирует первую обязательную команду start. */
private suspend fun WebSocketServerSession.receiveStart(): ChatStreamRequest? {
    val frame = incoming.receiveCatching().getOrNull() as? Frame.Text ?: run {
        sendEvent(ChatStreamEvent("error", error = "start command is required"))
        outgoing.close()
        return null
    }
    val start = try {
        streamingJson.decodeFromString<ChatStreamRequest>(frame.readText())
    } catch (_: Exception) {
        sendEvent(ChatStreamEvent("error", error = "invalid stream request"))
        outgoing.close()
        return null
    }
    if (start.type != "start" || start.message.isNullOrBlank()) {
        sendEvent(ChatStreamEvent("error", error = "start command with message is required"))
        outgoing.close()
        return null
    }
    if (start.sessionId != null && runCatching { UUID.fromString(start.sessionId) }.isFailure) {
        sendEvent(ChatStreamEvent("error", error = "sessionId must be a UUID"))
        outgoing.close()
        return null
    }
    return start
}

/** Запускает один совместно отменяемый Gemini и ElevenLabs pipeline. */
private fun WebSocketServerSession.launchStreamingTurn(
    conversationService: ConversationService,
    ttsClient: StreamingTtsClient,
    start: ChatStreamRequest,
): Job = launch {
    val textDeltas = Channel<String>(Channel.BUFFERED)
    val ttsJob = launch {
        ttsClient.synthesize(textDeltas.receiveAsFlow()).collect { pcm ->
            send(Frame.Binary(fin = true, data = pcm))
        }
    }
    try {
        val result = conversationService.replyStream(
            start.sessionId?.let(UUID::fromString),
            requireNotNull(start.message).trim(),
            onDelta = { delta ->
                sendEvent(ChatStreamEvent("delta", delta = delta))
                textDeltas.send(delta)
            },
            onSession = { sessionId -> sendEvent(ChatStreamEvent("session", sessionId = sessionId.toString())) },
        )
        textDeltas.close()
        ttsJob.join()
        sendEvent(ChatStreamEvent("done", sessionId = result.sessionId.toString()))
        streamingRoutesLogger.info("stream_completed sessionId={}", result.sessionId)
    } catch (_: SessionNotFoundException) {
        sendEvent(ChatStreamEvent("error", error = "chat session not found"))
    } catch (_: SessionFinishedException) {
        sendEvent(ChatStreamEvent("error", error = "training session is already finished"))
    } catch (exception: GeminiException) {
        sendEvent(ChatStreamEvent("error", error = exception.message ?: "Gemini stream failed"))
    } catch (exception: ElevenLabsException) {
        sendEvent(ChatStreamEvent("error", error = exception.message ?: "TTS stream failed"))
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        sendEvent(ChatStreamEvent("error", error = "stream failed"))
    } finally {
        textDeltas.close()
        ttsJob.cancel()
        outgoing.close()
    }
}

/** Отправляет безопасный сериализованный text event клиенту. */
private suspend fun WebSocketServerSession.sendEvent(event: ChatStreamEvent) {
    send(Frame.Text(streamingJson.encodeToString(ChatStreamEvent.serializer(), event)))
}
