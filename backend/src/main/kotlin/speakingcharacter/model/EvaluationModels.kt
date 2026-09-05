/** Описывает структурированный результат итоговой оценки тренировки. */
package speakingcharacter.model

import java.time.Instant
import java.util.UUID

/** Оценка одного фиксированного критерия тренировки. */
data class EvaluationCriterion(val name: String, val score: Int, val comment: String, val evidence: String)

/** Валидированный результат Gemini evaluation до сохранения в БД. */
data class EvaluationResult(
    val overallScore: Int,
    val summary: String,
    val recommendations: List<String>,
    val criteria: List<EvaluationCriterion>,
)

/** Постоянный отчёт о завершённой тренировочной сессии. */
data class TrainingReport(
    val id: UUID,
    val sessionId: UUID,
    val result: EvaluationResult,
    val createdAt: Instant,
)
