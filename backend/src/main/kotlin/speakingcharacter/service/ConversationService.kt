/** Координирует один ход активной тренировочной сессии. */
package speakingcharacter.service

import org.slf4j.LoggerFactory
import speakingcharacter.db.ChatRepository
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.SessionStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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

    /**
     * Выполняет потоковый ход: сохраняет user-реплику заранее, а assistant-реплику —
     * исключительно после штатного завершения Gemini stream.
     *
     * @param onDelta обработчик дельты, который передаёт текст в TTS и браузер
     */
    suspend fun replyStream(
        requestedSessionId: UUID?,
        userMessage: String,
        onDelta: suspend (String) -> Unit,
        onSession: suspend (UUID) -> Unit = {},
    ): ConversationResult {
        val session = withContext(Dispatchers.IO) {
            requestedSessionId?.let { chatRepository.findSession(it) ?: throw SessionNotFoundException() }
                ?: chatRepository.createSession("demo")
        }
        if (session.status == SessionStatus.FINISHED) throw SessionFinishedException()
        onSession(session.id)

        withContext(Dispatchers.IO) { chatRepository.addMessage(session.id, ChatRole.USER, userMessage) }
        val context = contextBuilder.build(
            withContext(Dispatchers.IO) {
                chatRepository.recentMessages(session.id, contextBuilder.contextLimit())
            },
        )
        val answer = StringBuilder()
        val startedAt = System.nanoTime()
        var firstDeltaLogged = false
        logger.info("Chat Gemini stream started for sessionId={}", session.id)
        try {
            llmClient.generateStream(promptProvider.getSystemPrompt(), context).collect { delta ->
                if (!firstDeltaLogged) {
                    firstDeltaLogged = true
                    logger.info("gemini_first_delta sessionId={} durationMs={}", session.id, (System.nanoTime() - startedAt) / 1_000_000)
                }
                answer.append(delta)
                onDelta(delta)
            }
        } catch (exception: GeminiException) {
            logger.error("Chat Gemini stream failed for sessionId={}: {}", session.id, exception.message)
            throw exception
        } finally {
            logger.info(
                "Chat Gemini stream ended sessionId={} durationMs={} completed={}",
                session.id,
                (System.nanoTime() - startedAt) / 1_000_000,
                firstDeltaLogged,
            )
        }
        val assistantMessage = answer.toString().trim()
        if (assistantMessage.isEmpty()) throw GeminiException("Gemini returned an empty response")
        withContext(Dispatchers.IO) {
            chatRepository.addMessage(session.id, ChatRole.ASSISTANT, assistantMessage)
            chatRepository.touchSession(session.id)
        }
        return ConversationResult(session.id, assistantMessage)
    }

    /** Возвращает полный сохранённый transcript запрошенной сессии. */
    suspend fun history(sessionId: UUID): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (chatRepository.findSession(sessionId) == null) throw SessionNotFoundException()
        chatRepository.history(sessionId)
    }
}
