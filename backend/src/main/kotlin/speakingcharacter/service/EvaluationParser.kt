/** Парсит и валидирует строгий JSON-ответ Gemini для итоговой оценки. */
package speakingcharacter.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import speakingcharacter.model.EvaluationCriterion
import speakingcharacter.model.EvaluationResult

/** Фиксированные критерии, ожидаемые от evaluation prompt. */
internal val evaluationCriteriaNames = listOf("Полнота ответа", "Следование сценарию", "Качество коммуникации")

/** Преобразует ответ Gemini в безопасный структурированный результат. */
internal fun parseEvaluationResult(rawResponse: String): EvaluationResult = try {
    val payload = Json.decodeFromString<EvaluationPayload>(rawResponse)
    val result = EvaluationResult(
        overallScore = payload.overallScore,
        summary = payload.summary.trim(),
        recommendations = payload.recommendations.map(String::trim),
        criteria = payload.criteria.map { item ->
            EvaluationCriterion(item.name.trim(), item.score, item.comment.trim(), item.evidence.trim())
        },
    )
    validateEvaluationResult(result)
    result
} catch (exception: EvaluationException) {
    throw exception
} catch (_: Exception) {
    throw EvaluationException("Gemini returned invalid evaluation JSON")
}

/** Проверяет fixed criteria, диапазоны score и обязательные текстовые поля. */
internal fun validateEvaluationResult(result: EvaluationResult) {
    if (result.overallScore !in 1..5) throw EvaluationException("evaluation overallScore must be between 1 and 5")
    if (result.summary.isBlank()) throw EvaluationException("evaluation summary must not be empty")
    if (result.recommendations.size !in 1..3 || result.recommendations.any(String::isBlank)) {
        throw EvaluationException("evaluation recommendations must contain from 1 to 3 items")
    }
    if (result.criteria.size != evaluationCriteriaNames.size || result.criteria.map(EvaluationCriterion::name).toSet() != evaluationCriteriaNames.toSet()) {
        throw EvaluationException("evaluation must contain all fixed criteria exactly once")
    }
    result.criteria.forEach { criterion ->
        if (criterion.score !in 1..5) throw EvaluationException("evaluation criterion score must be between 1 and 5")
        if (criterion.comment.isBlank() || criterion.evidence.isBlank()) throw EvaluationException("evaluation criterion text must not be empty")
    }
}

/** Сериализуемая форма JSON, ожидаемая от Gemini. */
@Serializable
private data class EvaluationPayload(
    val overallScore: Int,
    val summary: String,
    val recommendations: List<String>,
    val criteria: List<EvaluationCriterionPayload>,
)

/** Сериализуемая форма одного критерия JSON-ответа. */
@Serializable
private data class EvaluationCriterionPayload(val name: String, val score: Int, val comment: String, val evidence: String)
