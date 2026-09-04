/** Координирует один ход активной тренировочной сессии. */
package speakingcharacter.service

import org.slf4j.LoggerFactory
import speakingcharacter.db.ChatRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.SessionStatus
import java.util.UUID
import kotlin.time.measureTime

/** Данные ответа, возвращаемые после одного завершённого хода диалога. */
data class ConversationResult(val sessionId: UUID, val assistantMessage: String)

/** Выполняет persistence и inference workflow обычного хода training conversation. */
class ConversationService(
    private val chatRepository: ChatRepository,
    private val contextBuilder: ConversationContextBuilder,
    private val promptProvider: PromptProvider,
    private val llmClient: LlmClient,
) {
    private val logger = LoggerFactory.getLogger(ConversationService::class.java)

    /** Сохраняет user turn, генерирует assistant turn и возвращает ответ активной сессии. */
    suspend fun reply(requestedSessionId: UUID?, userMessage: String): ConversationResult {
        val session = requestedSessionId?.let { chatRepository.findSession(it) ?: throw SessionNotFoundException() }
            ?: chatRepository.createSession("demo")
        if (session.status == SessionStatus.FINISHED) throw SessionFinishedException()

        chatRepository.addMessage(session.id, ChatRole.USER, userMessage)
        val context = contextBuilder.build(chatRepository.recentMessages(session.id, contextBuilder.contextLimit()))
        logger.info("Chat Gemini request started for sessionId={}", session.id)
        var assistantMessage: String? = null
        val duration = try {
            measureTime { assistantMessage = llmClient.generate(promptProvider.getSystemPrompt(), context) }
        } catch (exception: GeminiException) {
            logger.error("Chat Gemini request failed for sessionId={}: {}", session.id, exception.message)
            throw exception
        }
        logger.info("Chat Gemini request completed for sessionId={} durationMs={}", session.id, duration.inWholeMilliseconds)
        chatRepository.addMessage(session.id, ChatRole.ASSISTANT, requireNotNull(assistantMessage))
        chatRepository.touchSession(session.id)
        return ConversationResult(session.id, requireNotNull(assistantMessage))
    }

    /** Возвращает полный сохранённый transcript запрошенной сессии. */
    fun history(sessionId: UUID): List<ChatMessage> {
        if (chatRepository.findSession(sessionId) == null) throw SessionNotFoundException()
        return chatRepository.history(sessionId)
    }
}
