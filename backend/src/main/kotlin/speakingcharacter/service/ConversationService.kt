/** Координирует один ход активной тренировочной сессии. */
package speakingcharacter.service

import org.slf4j.LoggerFactory
import speakingcharacter.db.ChatRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.SessionStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val session = withContext(Dispatchers.IO) {
            requestedSessionId?.let { chatRepository.findSession(it) ?: throw SessionNotFoundException() }
                ?: chatRepository.createSession("demo")
        }
        if (session.status == SessionStatus.FINISHED) throw SessionFinishedException()

        withContext(Dispatchers.IO) { chatRepository.addMessage(session.id, ChatRole.USER, userMessage) }
        val context = contextBuilder.build(
            withContext(Dispatchers.IO) {
                chatRepository.recentMessages(session.id, contextBuilder.contextLimit())
            },
        )
        logger.info("Chat Gemini request started for sessionId={}", session.id)
        var assistantMessage: String? = null
        val duration = try {
            measureTime { assistantMessage = llmClient.generate(promptProvider.getSystemPrompt(), context) }
        } catch (exception: GeminiException) {
            logger.error("Chat Gemini request failed for sessionId={}: {}", session.id, exception.message)
            throw exception
        }
        logger.info("Chat Gemini request completed for sessionId={} durationMs={}", session.id, duration.inWholeMilliseconds)
        withContext(Dispatchers.IO) {
            chatRepository.addMessage(session.id, ChatRole.ASSISTANT, requireNotNull(assistantMessage))
            chatRepository.touchSession(session.id)
        }
        return ConversationResult(session.id, requireNotNull(assistantMessage))
    }

    /** Возвращает полный сохранённый transcript запрошенной сессии. */
    suspend fun history(sessionId: UUID): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (chatRepository.findSession(sessionId) == null) throw SessionNotFoundException()
        chatRepository.history(sessionId)
    }
}
