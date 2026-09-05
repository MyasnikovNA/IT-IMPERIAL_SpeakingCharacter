/** Определяет операции хранения тренировочных сессий и сообщений. */
package speakingcharacter.db

import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.TrainingSession
import java.util.UUID

/** Минимальный контракт persistence для orchestration сервисов и unit-тестов. */
interface ChatRepository {
    /** Создаёт новую активную сессию указанного сценария. */
    fun createSession(scenarioId: String): TrainingSession

    /** Возвращает сессию или null, если её нет. */
    fun findSession(sessionId: UUID): TrainingSession?

    /** Сохраняет одну реплику пользователя или ассистента. */
    fun addMessage(sessionId: UUID, role: ChatRole, content: String)

    /** Возвращает последние сообщения в хронологическом порядке. */
    fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage>

    /** Возвращает полный transcript сессии в хронологическом порядке. */
    fun history(sessionId: UUID): List<ChatMessage>

    /** Обновляет метку последней активности активной сессии. */
    fun touchSession(sessionId: UUID)
}
