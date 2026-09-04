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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

/** Тестирует единственный successful turn: session, delta, PCM и done. */
class StreamingRoutesTest {
    /** Сохраняет стабильный порядок text и binary frames для браузерного Simli relay. */
    @Test
    fun `stream websocket sends session delta PCM and done in order`() = testApplication {
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
            val pcm = incoming.receive() as Frame.Binary
            val done = incoming.receive() as Frame.Text

            assertEquals("session", eventType(session))
            assertEquals("delta", eventType(delta))
            assertEquals("done", eventType(done))
            assertContentEquals("Ответ".encodeToByteArray(), pcm.data)
        }
    }

    /** Извлекает тип из server text frame без зависимости от private JSON объекта routes. */
    private fun eventType(frame: Frame.Text): String = frame.readText().substringAfter("\"type\":\"").substringBefore('"')

    /** Возвращает streaming-конфигурацию с секретами только для backend-ветки теста. */
    private fun testConfig(): AppConfig = AppConfig(
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

    /** Преобразует каждую дельту в PCM-байты для проверки relay без ElevenLabs. */
    private class EchoTtsClient : StreamingTtsClient {
        /** Повторяет байты текста в качестве тестового PCM frame. */
        override fun synthesize(textDeltas: Flow<String>): Flow<ByteArray> = kotlinx.coroutines.flow.flow {
            textDeltas.collect { emit(it.encodeToByteArray()) }
        }
    }
}
