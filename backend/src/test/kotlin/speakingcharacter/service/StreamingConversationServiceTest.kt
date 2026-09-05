/** Проверяет persistence правила потокового хода без PostgreSQL. */
package speakingcharacter.service

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import speakingcharacter.db.ChatRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.LlmMessage
import speakingcharacter.model.SessionStatus
import speakingcharacter.model.TrainingSession

/** Тестирует сохранение assistant только после успешного завершения stream. */
class StreamingConversationServiceTest {
    /** Сохраняет собранный assistant ответ после normal completion и не дублирует user turn. */
    @Test
    fun `stream reply persists assistant after successful completion`() = runTest {
        val repository = MemoryChatRepository()
        val service = service(repository, flowOf("Добрый ", "день"))

        val result = service.replyStream(null, "Здравствуйте", onDelta = { })

        assertEquals("Добрый день", result.assistantMessage)
        assertEquals(listOf("Здравствуйте", "Добрый день"), repository.history(result.sessionId).map(ChatMessage::content))
    }

    /** Оставляет только user turn при ошибке после первой дельты, не создавая ложный assistant. */
    @Test
    fun `stream reply does not persist partial answer after failure`() = runTest {
        val repository = MemoryChatRepository()
        val service = service(repository, flow {
            emit("Неполный ")
            throw GeminiException("stream interrupted")
        })
        var sessionId: UUID? = null

        assertFailsWith<GeminiException> {
            service.replyStream(null, "Здравствуйте", onDelta = { }, onSession = { sessionId = it })
        }

        assertEquals(listOf("Здравствуйте"), repository.history(requireNotNull(sessionId)).map(ChatMessage::content))
    }

    /** Не сохраняет assistant при отмене browser WebSocket и пробрасывает cancellation. */
    @Test
    fun `stream reply keeps only user turn after cancellation`() = runTest {
        val repository = MemoryChatRepository()
        val service = service(repository, flow {
            emit("Уже отправлено")
            throw CancellationException("cancelled")
        })
        var sessionId: UUID? = null

        assertFailsWith<CancellationException> {
            service.replyStream(null, "Здравствуйте", onDelta = { }, onSession = { sessionId = it })
        }

        assertEquals(listOf("Здравствуйте"), repository.history(requireNotNull(sessionId)).map(ChatMessage::content))
    }

    /** Собирает service с заданным искусственным Gemini stream. */
    private fun service(repository: MemoryChatRepository, stream: Flow<String>): ConversationService =
        ConversationService(repository, ConversationContextBuilder(20), PromptProvider(), StreamLlmClient(stream))

    /** Минимальная in-memory ChatRepository для проверки побочных эффектов persistence. */
    private class MemoryChatRepository : ChatRepository {
        private val sessions = mutableMapOf<UUID, TrainingSession>()
        private val messages = mutableMapOf<UUID, MutableList<ChatMessage>>()

        /** Создаёт active session. */
        override fun createSession(scenarioId: String): TrainingSession = TrainingSession(
            UUID.randomUUID(), SessionStatus.ACTIVE, scenarioId, null,
        ).also { session -> sessions[session.id] = session; messages[session.id] = mutableListOf() }

        /** Находит session. */
        override fun findSession(sessionId: UUID): TrainingSession? = sessions[sessionId]

        /** Добавляет transcript message. */
        override fun addMessage(sessionId: UUID, role: ChatRole, content: String) {
            messages.getValue(sessionId).add(ChatMessage(role, content, Instant.now()))
        }

        /** Возвращает контекстное окно. */
        override fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage> = messages.getValue(sessionId).takeLast(limit)

        /** Возвращает весь transcript. */
        override fun history(sessionId: UUID): List<ChatMessage> = messages.getValue(sessionId).toList()

        /** Не требует обновления времени в fake persistence. */
        override fun touchSession(sessionId: UUID) = Unit
    }

    /** Возвращает заданный Flow без реальных Gemini HTTP-вызовов. */
    private class StreamLlmClient(private val stream: Flow<String>) : LlmClient {
        /** Не используется в streaming-тесте. */
        override suspend fun generate(systemPrompt: String, messages: List<LlmMessage>): String = error("Не используется")

        /** Не используется в streaming-тесте. */
        override suspend fun generateStructuredJson(systemPrompt: String, messages: List<LlmMessage>): String = error("Не используется")

        /** Возвращает зафиксированный test stream. */
        override fun generateStream(systemPrompt: String, messages: List<LlmMessage>): Flow<String> = stream
    }
}
