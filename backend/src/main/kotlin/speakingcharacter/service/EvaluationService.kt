/** Координирует идемпотентное формирование и сохранение итоговой оценки тренировки. */
package speakingcharacter.service

import org.slf4j.LoggerFactory
import speakingcharacter.db.ChatRepository
import speakingcharacter.db.EvaluationRepository
import speakingcharacter.model.SessionStatus
import speakingcharacter.model.TrainingReport
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.measureTime

/** Выполняет finish workflow без удержания JDBC-транзакции во время Gemini inference. */
class EvaluationService(
    private val chatRepository: ChatRepository,
    private val evaluationRepository: EvaluationRepository,
    private val contextBuilder: ConversationContextBuilder,
    private val promptProvider: PromptProvider,
    private val llmClient: LlmClient,
) {
    private val logger = LoggerFactory.getLogger(EvaluationService::class.java)

    /** Возвращает existing report либо генерирует, валидирует и сохраняет новый report. */
    suspend fun finishSession(sessionId: UUID): TrainingReport {
        val session = withContext(Dispatchers.IO) {
            chatRepository.findSession(sessionId) ?: throw SessionNotFoundException()
        }
        if (session.status == SessionStatus.FINISHED) {
            return withContext(Dispatchers.IO) { evaluationRepository.findReport(sessionId) }
                ?: throw EvaluationException("finished training session has no report")
        }

        val transcript = withContext(Dispatchers.IO) { chatRepository.history(sessionId) }
        if (transcript.isEmpty()) throw IllegalStateException("training session has no messages")
        logger.info("Evaluation Gemini request started for sessionId={}", sessionId)
        var rawEvaluation: String? = null
        val duration = try {
            measureTime {
                rawEvaluation = llmClient.generateStructuredJson(
                    promptProvider.getEvaluationPrompt(),
                    contextBuilder.buildFullTranscript(transcript),
                )
            }
        } catch (exception: GeminiException) {
            logger.error("Evaluation Gemini request failed for sessionId={}: {}", sessionId, exception.message)
            throw EvaluationException("Gemini evaluation request failed")
        }
        logger.info("Evaluation Gemini request completed for sessionId={} durationMs={}", sessionId, duration.inWholeMilliseconds)
        val result = try {
            parseEvaluationResult(requireNotNull(rawEvaluation))
        } catch (exception: EvaluationException) {
            logger.error("Evaluation parsing failed for sessionId={}: {}", sessionId, exception.message)
            throw exception
        }
        val report = TrainingReport(UUID.randomUUID(), sessionId, result, Instant.now())
        return withContext(Dispatchers.IO) { evaluationRepository.saveReportAndFinishSession(report) }
    }

    /** Возвращает существующий report либо сообщает, что он ещё не был сформирован. */
    suspend fun getReport(sessionId: UUID): TrainingReport = withContext(Dispatchers.IO) {
        if (chatRepository.findSession(sessionId) == null) throw SessionNotFoundException()
        evaluationRepository.findReport(sessionId) ?: throw NoSuchElementException("training report not found")
    }
}
