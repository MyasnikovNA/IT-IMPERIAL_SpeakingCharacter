/** Проверяет порядок кадров WebSocket pipeline с in-memory зависимостями. */
package speakingcharacter.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import speakingcharacter.config.AppConfig
import speakingcharacter.config.AvatarProvider
import speakingcharacter.db.ChatRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.LlmMessage
import speakingcharacter.model.SessionStatus
import speakingcharacter.model.TrainingSession
import speakingcharacter.service.ConversationContextBuilder
import speakingcharacter.service.ConversationService
import speakingcharacter.service.LlmClient
import speakingcharacter.service.PromptProvider
import speakingcharacter.service.SimliSessionTokenClient
import speakingcharacter.service.StreamingTtsClient
import speakingcharacter.service.TtsAlignment
import speakingcharacter.service.TtsAudioFrame

/** Тестирует единственный successful turn: session, delta, subtitle metadata, PCM и done. */
class StreamingRoutesTest {
    /** Сохраняет стабильный порядок text и binary frames для браузерного Simli relay. */
    @Test
    fun `stream websocket sends subtitle metadata before matching PCM`() = testApplication {
        val config = testConfig()
        val conversationService = ConversationService(
            MemoryRepository(),
            ConversationContextBuilder(20),
            PromptProvider(),
            FixedLlmClient(),
        )
        application {
            install(ServerWebSockets)
            registerStreamingRoutes(
                config,
                conversationService,
                EchoTtsClient(),
                SimliSessionTokenClient(HttpClient(MockEngine { error("Simli token endpoint не вызывается") }), config),
            )
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/api/chat/stream") {
            send(Frame.Text("""{"type":"start","message":"Здравствуйте"}"""))

            val session = incoming.receive() as Frame.Text
            val delta = incoming.receive() as Frame.Text
            val audioFrame = incoming.receive() as Frame.Text
            val pcm = incoming.receive() as Frame.Binary
            val metrics = incoming.receive() as Frame.Text
            val done = incoming.receive() as Frame.Text

            assertEquals("session", eventType(session))
            assertEquals("delta", eventType(delta))
            assertEquals("audio_frame", eventType(audioFrame))
            assertEquals("metrics", eventType(metrics))
            assertEquals("done", eventType(done))
            assertEquals(true, audioFrame.readText().contains("\"frameId\":0"))
            assertEquals(true, audioFrame.readText().contains("\"text\":\"Ответ\""))
            assertContentEquals("Ответ".encodeToByteArray(), pcm.data)
        }
    }

    /** Возвращает browser error и outcome tts_failed, если дочерний TTS-поток завершается ошибкой. */
    @Test
    fun `stream websocket reports TTS failure instead of silent cancellation`() = testApplication {
        installStreamingRoute(FailingTtsClient(), testConfig())
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/api/chat/stream") {
            send(Frame.Text("""{"type":"start","message":"Здравствуйте"}"""))

            assertEquals("session", eventType(incoming.receive() as Frame.Text))
            assertEquals("delta", eventType(incoming.receive() as Frame.Text))
            val metrics = incoming.receive() as Frame.Text
            val error = incoming.receive() as Frame.Text

            assertEquals("metrics", eventType(metrics))
            assertContains(metrics.readText(), "tts_failed")
            assertEquals("error", eventType(error))
            assertContains(error.readText(), "TTS unavailable")
        }
    }

    /** Ограничивает ожидание TTS, который не завершается после закрытия Gemini stream. */
    @Test
    fun `stream websocket times out hanging TTS and sends error`() = testApplication {
        installStreamingRoute(HangingTtsClient(), testConfig(ttsCompletionTimeoutMillis = 50))
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/api/chat/stream") {
            send(Frame.Text("""{"type":"start","message":"Здравствуйте"}"""))

            assertEquals("session", eventType(incoming.receive() as Frame.Text))
            assertEquals("delta", eventType(incoming.receive() as Frame.Text))
            val metrics = incoming.receive() as Frame.Text
            val error = incoming.receive() as Frame.Text

            assertEquals("metrics", eventType(metrics))
            assertContains(metrics.readText(), "tts_failed")
            assertEquals("error", eventType(error))
            assertContains(error.readText(), "completion timed out")
        }
    }

    /** Регистрирует минимальный streaming-маршрут с указанной TTS fake для failure-проверок. */
    private fun io.ktor.server.testing.ApplicationTestBuilder.installStreamingRoute(ttsClient: StreamingTtsClient, config: AppConfig) {
        val conversationService = ConversationService(
            MemoryRepository(),
            ConversationContextBuilder(20),
            PromptProvider(),
            FixedLlmClient(),
        )
        application {
            install(ServerWebSockets)
            registerStreamingRoutes(
                config,
                conversationService,
                ttsClient,
                SimliSessionTokenClient(HttpClient(MockEngine { error("Simli token endpoint не вызывается") }), config),
            )
        }
    }

    /** Извлекает тип из server text frame без зависимости от private JSON объекта routes. */
    private fun eventType(frame: Frame.Text): String = frame.readText().substringAfter("\"type\":\"").substringBefore('"')

    /** Возвращает streaming-конфигурацию с секретами только для backend-ветки теста. */
    private fun testConfig(ttsCompletionTimeoutMillis: Long = 30_000): AppConfig = AppConfig(
        geminiApiKey = "gemini-key",
        geminiModel = "model",
        databaseUrl = "jdbc:postgresql://unused",
        databaseUser = "unused",
        databasePassword = "unused",
        maxContextMessages = 20,
        frontendHost = "localhost:8000",
        avatarProvider = AvatarProvider.SIMLI,
        simliApiKey = "simli-key",
        simliFaceId = "face-id",
        elevenLabsApiKey = "eleven-key",
        elevenLabsVoiceId = "voice-id",
        ttsCompletionTimeoutMillis = ttsCompletionTimeoutMillis,
    )

    /** Минимально сохраняет transcript, необходимый ConversationService. */
    private class MemoryRepository : ChatRepository {
        private val sessions = mutableMapOf<UUID, TrainingSession>()
        private val messages = mutableMapOf<UUID, MutableList<ChatMessage>>()

        /** Создаёт активную session. */
        override fun createSession(scenarioId: String): TrainingSession = TrainingSession(
            UUID.randomUUID(), SessionStatus.ACTIVE, scenarioId, null,
        ).also { session -> sessions[session.id] = session; messages[session.id] = mutableListOf() }

        /** Находит session. */
        override fun findSession(sessionId: UUID): TrainingSession? = sessions[sessionId]

        /** Добавляет message. */
        override fun addMessage(sessionId: UUID, role: ChatRole, content: String) {
            messages.getValue(sessionId).add(ChatMessage(role, content, Instant.now()))
        }

        /** Возвращает последние сообщения. */
        override fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage> = messages.getValue(sessionId).takeLast(limit)

        /** Возвращает history. */
        override fun history(sessionId: UUID): List<ChatMessage> = messages.getValue(sessionId).toList()

        /** Не нужен в fake persistence. */
        override fun touchSession(sessionId: UUID) = Unit
    }

    /** Генерирует одну дельту Gemini. */
    private class FixedLlmClient : LlmClient {
        /** Не используется маршрутом streaming. */
        override suspend fun generate(systemPrompt: String, messages: List<LlmMessage>): String = error("Не используется")

        /** Не используется маршрутом streaming. */
        override suspend fun generateStructuredJson(systemPrompt: String, messages: List<LlmMessage>): String = error("Не используется")

        /** Возвращает одну Gemini дельту. */
        override fun generateStream(systemPrompt: String, messages: List<LlmMessage>): Flow<String> = flowOf("Ответ")
    }

    /** Преобразует каждую дельту в PCM и alignment для проверки subtitle relay без ElevenLabs. */
    private class EchoTtsClient : StreamingTtsClient {
        /** Повторяет байты текста в качестве тестового PCM frame с искусственным alignment. */
        override fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame> = kotlinx.coroutines.flow.flow {
            textDeltas.collect { text ->
                emit(
                    TtsAudioFrame(
                        pcm = text.encodeToByteArray(),
                        alignment = TtsAlignment(
                            chars = text.map(Char::toString),
                            charStartTimesMs = text.indices.map { it.toLong() * 50 },
                            charDurationsMs = List(text.length) { 50 },
                        ),
                    ),
                )
            }
        }
    }

    /** Завершается ошибкой после старта, моделируя отказ ElevenLabs. */
    private class FailingTtsClient : StreamingTtsClient {
        /** Сообщает безопасную provider-ошибку без отмены родительского WebSocket job. */
        override fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame> = kotlinx.coroutines.flow.flow {
            throw speakingcharacter.service.ElevenLabsException("TTS unavailable")
        }
    }

    /** Не завершается до отмены, моделируя provider без final frame. */
    private class HangingTtsClient : StreamingTtsClient {
        /** Удерживает collect открытым до bounded cancellation маршрута. */
        override fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame> = kotlinx.coroutines.flow.flow {
            awaitCancellation()
        }
    }
}
