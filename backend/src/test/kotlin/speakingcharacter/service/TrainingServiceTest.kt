/** Проверяет lifecycle завершения тренировки без PostgreSQL и реального Gemini. */
package speakingcharacter.service

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import speakingcharacter.db.ChatRepository
import speakingcharacter.db.EvaluationRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.EvaluationResult
import speakingcharacter.model.LlmMessage
import speakingcharacter.model.SessionStatus
import speakingcharacter.model.TrainingReport
import speakingcharacter.model.TrainingSession
import java.time.Instant
import java.util.UUID

/** Тестирует запрет новых реплик и идемпотентность finish через маленькие in-memory fake. */
class TrainingServiceTest {
    /** Не позволяет продолжить диалог после FINISHED session. */
    @Test
    fun `reply rejects finished session`() = runTest {
        val chatRepository = InMemoryChatRepository().apply { createFinishedSession() }
        val service = ConversationService(chatRepository, ConversationContextBuilder(20), PromptProvider(), FakeLlmClient())
        val sessionId = chatRepository.onlySessionId()

        assertFailsWith<SessionFinishedException> { service.reply(sessionId, "Новая реплика") }
    }

    /** Повторный finish возвращает saved report без второго обращения к LLM. */
    @Test
    fun `finish returns existing report without second generation`() = runTest {
        val chatRepository = InMemoryChatRepository().apply { createActiveSessionWithMessage() }
        val llmClient = FakeLlmClient()
        val evaluationRepository = InMemoryEvaluationRepository(chatRepository)
        val service = EvaluationService(chatRepository, evaluationRepository, ConversationContextBuilder(20), PromptProvider(), llmClient)
        val sessionId = chatRepository.onlySessionId()

        val first = service.finishSession(sessionId)
        val second = service.finishSession(sessionId)

        assertEquals(first, second)
        assertEquals(1, llmClient.structuredGenerationCalls)
    }

    /** Простая in-memory реализация chat persistence для service unit-тестов. */
    private class InMemoryChatRepository : ChatRepository {
        private val sessions = mutableMapOf<UUID, TrainingSession>()
        private val messages = mutableMapOf<UUID, MutableList<ChatMessage>>()

        /** Создаёт активную сессию в памяти. */
        override fun createSession(scenarioId: String): TrainingSession {
            val session = TrainingSession(UUID.randomUUID(), SessionStatus.ACTIVE, scenarioId, null)
            sessions[session.id] = session
            messages[session.id] = mutableListOf()
            return session
        }

        /** Возвращает состояние сессии из памяти. */
        override fun findSession(sessionId: UUID): TrainingSession? = sessions[sessionId]

        /** Сохраняет message в памяти. */
        override fun addMessage(sessionId: UUID, role: ChatRole, content: String) {
            messages.getValue(sessionId).add(ChatMessage(role, content, Instant.now()))
        }

        /** Возвращает ограниченное окно messages. */
        override fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage> = messages.getValue(sessionId).takeLast(limit)

        /** Возвращает полный history. */
        override fun history(sessionId: UUID): List<ChatMessage> = messages.getValue(sessionId).toList()

        /** Не требует действия для in-memory fake. */
        override fun touchSession(sessionId: UUID) = Unit

        /** Создаёт единственную завершённую session для проверки запрета reply. */
        fun createFinishedSession() {
            val session = createSession("demo")
            sessions[session.id] = session.copy(status = SessionStatus.FINISHED, finishedAt = Instant.now())
        }

        /** Создаёт активную session с минимальным transcript для evaluation. */
        fun createActiveSessionWithMessage() {
            val session = createSession("demo")
            addMessage(session.id, ChatRole.USER, "Меня зовут Тарас")
        }

        /** Возвращает id единственной test session. */
        fun onlySessionId(): UUID = sessions.keys.single()

        /** Переводит session в FINISHED после сохранения report. */
        fun finish(sessionId: UUID) {
            sessions[sessionId] = sessions.getValue(sessionId).copy(status = SessionStatus.FINISHED, finishedAt = Instant.now())
        }
    }

    /** In-memory storage report, синхронизирующее статус сессии. */
    private class InMemoryEvaluationRepository(private val chatRepository: InMemoryChatRepository) : EvaluationRepository {
        private val reports = mutableMapOf<UUID, TrainingReport>()

        /** Возвращает report для session. */
        override fun findReport(sessionId: UUID): TrainingReport? = reports[sessionId]

        /** Сохраняет report и завершает session в памяти. */
        override fun saveReportAndFinishSession(report: TrainingReport): TrainingReport {
            reports[report.sessionId] = report
            chatRepository.finish(report.sessionId)
            return report
        }
    }

    /** Предоставляет фиксированный валидный JSON без вызова внешнего Gemini. */
    private class FakeLlmClient : LlmClient {
        var structuredGenerationCalls = 0

        /** Возвращает текст для обычного chat turn. */
        override suspend fun generate(systemPrompt: String, messages: List<LlmMessage>): String = "Ответ"

        /** Возвращает валидный JSON и считает вызовы evaluation. */
        override suspend fun generateStructuredJson(systemPrompt: String, messages: List<LlmMessage>): String {
            structuredGenerationCalls += 1
            return """{"overallScore":4,"summary":"Итог","recommendations":["Совет"],"criteria":[{"name":"Полнота ответа","score":4,"comment":"Комментарий","evidence":"Фрагмент"},{"name":"Следование сценарию","score":4,"comment":"Комментарий","evidence":"Фрагмент"},{"name":"Качество коммуникации","score":4,"comment":"Комментарий","evidence":"Фрагмент"}]}"""
        }

        /** Возвращает одну тестовую дельту для соблюдения streaming-контракта. */
        override fun generateStream(systemPrompt: String, messages: List<LlmMessage>): Flow<String> = flowOf("Ответ")
    }
}
