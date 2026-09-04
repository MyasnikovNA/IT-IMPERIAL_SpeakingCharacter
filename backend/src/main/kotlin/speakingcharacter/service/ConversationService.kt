/** Координирует сохранение сессии, контекст, Gemini и запись ответа. */
package speakingcharacter.service

import org.slf4j.LoggerFactory
import speakingcharacter.config.AppConfig
import speakingcharacter.db.ChatMessage
import speakingcharacter.db.ChatRepository
import speakingcharacter.db.ChatRole
import java.util.UUID
import kotlin.time.measureTime

/** Данные ответа, возвращаемые после полного хода пользователя и ассистента. */
data class ConversationResult(val sessionId: UUID, val assistantMessage: String)

/** Выполняет простой MVP-поток постоянной памяти диалога. */
class ConversationService(
    private val repository: ChatRepository,
    private val geminiClient: GeminiClient,
    private val systemPrompt: String,
    private val config: AppConfig,
) {
    private val logger = LoggerFactory.getLogger(ConversationService::class.java)

    /**
     * Сохраняет реплику пользователя, вызывает Gemini с ограниченной историей и сохраняет ответ.
     *
     * @param requestedSessionId существующая сессия или null для начала диалога
     * @param userMessage предварительно валидированный непустой ввод пользователя
     */
    suspend fun reply(requestedSessionId: UUID?, userMessage: String): ConversationResult {
        val sessionId = requestedSessionId ?: repository.createSession()
        if (requestedSessionId != null && !repository.sessionExists(sessionId)) {
            throw NoSuchElementException("Chat session not found")
        }

        repository.addMessage(sessionId, ChatRole.USER, userMessage)
        val context = ContextWindow.select(repository.recentMessages(sessionId, config.maxContextMessages), config.maxContextMessages)
        logger.info("Gemini request started for sessionId={}", sessionId)
        var assistantMessage: String? = null
        val duration = try {
            measureTime {
                assistantMessage = geminiClient.generate(systemPrompt, context)
            }
        } catch (exception: GeminiException) {
            logger.error("Gemini request failed for sessionId={}: {}", sessionId, exception.message)
            throw exception
        }
        logger.info("Gemini request completed for sessionId={} durationMs={}", sessionId, duration.inWholeMilliseconds)
        repository.addMessage(sessionId, ChatRole.ASSISTANT, requireNotNull(assistantMessage))
        repository.touchSession(sessionId)
        return ConversationResult(sessionId, requireNotNull(assistantMessage))
    }

    /** Возвращает полную хронологическую историю при существующей сессии. */
    fun history(sessionId: UUID): List<ChatMessage> {
        if (!repository.sessionExists(sessionId)) throw NoSuchElementException("Chat session not found")
        return repository.history(sessionId)
    }
}
