/** Содержит JSON DTO для endpoint'ов завершения тренировки и получения report. */
package speakingcharacter.api

import kotlinx.serialization.Serializable
import speakingcharacter.model.TrainingReport

/** Один criterion, возвращаемый frontend или API-клиенту. */
@Serializable
data class EvaluationCriterionDto(val name: String, val score: Int, val comment: String, val evidence: String)

/** Полный structured report завершённой тренировки. */
@Serializable
data class TrainingReportDto(
    val sessionId: String,
    val overallScore: Int,
    val summary: String,
    val recommendations: List<String>,
    val criteria: List<EvaluationCriterionDto>,
)

/** Преобразует domain report в стабильный JSON contract. */
fun TrainingReport.toDto(): TrainingReportDto = TrainingReportDto(
    sessionId = sessionId.toString(),
    overallScore = result.overallScore,
    summary = result.summary,
    recommendations = result.recommendations,
    criteria = result.criteria.map { criterion ->
        EvaluationCriterionDto(criterion.name, criterion.score, criterion.comment, criterion.evidence)
    },
)
