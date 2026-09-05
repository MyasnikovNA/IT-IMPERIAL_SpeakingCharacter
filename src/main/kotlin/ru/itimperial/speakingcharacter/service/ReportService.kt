package ru.itimperial.speakingcharacter.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.itimperial.speakingcharacter.llm.LlmClient
import ru.itimperial.speakingcharacter.model.CriterionScore
import ru.itimperial.speakingcharacter.model.MessageRole
import ru.itimperial.speakingcharacter.model.TrainingMessage
import ru.itimperial.speakingcharacter.model.TrainingReport
import java.time.Instant

/** Сообщает, что Gemini не сформировал корректный структурированный отчёт. */
class EvaluationException(message: String) : RuntimeException(message)

/** Формирует, разбирает и строго валидирует итоговую оценку корпоративной тренировки. */
class ReportService(private val llmClient: LlmClient, private val json: Json) {
    /** Строит отчёт только из полного transcript и ожидаемого набора критериев. */
    suspend fun build(messages: List<TrainingMessage>, expectedCriteria: List<String>): TrainingReport {
        if (messages.isEmpty()) throw IllegalStateException("training session has no messages")
        val criteria = expectedCriteria.map(String::trim)
        if (criteria.isEmpty() || criteria.toSet().size != criteria.size) {
            throw IllegalArgumentException("evaluation criteria must be non-empty and unique")
        }
        val transcript = messages.joinToString("\n") { message ->
            val role = if (message.role == MessageRole.USER) "Сотрудник" else "AI-тренер"
            "$role${if (message.interrupted) " [прервано]" else ""}: ${message.text}"
        }
        val systemPrompt = """
            Ты методист, оценивающий корпоративную тренировку. Используй только ожидаемые критерии,
            не придумывай дополнительные. Верни только JSON без Markdown.
            Ожидаемые критерии: ${criteria.joinToString("; ")}
        """.trimIndent()
        val prompt = """
            Оцени полную стенограмму тренировки.
            Верни JSON строго вида:
            {
              "overallScore": 1,
              "summary": "краткий итог",
              "recommendations": ["конкретная рекомендация"],
              "criteria": [
                {"name":"ожидаемый критерий","score":1,"comment":"комментарий","evidence":"цитата или факт из стенограммы"}
              ]
            }
            overallScore и каждый score — целые числа от 1 до 5. recommendations содержит от 1 до 3 непустых пунктов.
            В criteria должны быть ровно все ожидаемые критерии, по одному разу.

            Стенограмма:
            $transcript
        """.trimIndent()
        val raw = try {
            llmClient.generateText(prompt, systemPrompt, jsonMode = true)
        } catch (exception: Exception) {
            throw EvaluationException("Gemini evaluation request failed")
        }
        val result = parse(raw, criteria)
        return TrainingReport(
            generatedAt = Instant.now().toString(),
            overallScore = result.overallScore,
            summary = result.summary,
            recommendations = result.recommendations,
            criteria = result.criteria,
            evaluationStatus = "EVALUATED",
        )
    }

    /** Разбирает и проверяет JSON Gemini до изменения статуса сессии. */
    private fun parse(raw: String, expectedCriteria: List<String>): EvaluationResult = try {
        val payload = json.decodeFromString<EvaluationPayload>(cleanJson(raw))
        val result = EvaluationResult(
            overallScore = payload.overallScore,
            summary = payload.summary.trim(),
            recommendations = payload.recommendations.map(String::trim),
            criteria = payload.criteria.map { CriterionScore(it.name.trim(), it.score, it.comment.trim(), it.evidence.trim()) },
        )
        validate(result, expectedCriteria)
        result
    } catch (exception: EvaluationException) {
        throw exception
    } catch (_: Exception) {
        throw EvaluationException("Gemini returned invalid evaluation JSON")
    }

    /** Проверяет диапазоны оценок, обязательные поля и точный набор критериев. */
    private fun validate(result: EvaluationResult, expectedCriteria: List<String>) {
        if (result.overallScore !in 1..5) throw EvaluationException("evaluation overallScore must be between 1 and 5")
        if (result.summary.isBlank()) throw EvaluationException("evaluation summary must not be empty")
        if (result.recommendations.size !in 1..3 || result.recommendations.any(String::isBlank)) throw EvaluationException("evaluation recommendations must contain from 1 to 3 items")
        if (result.criteria.size != expectedCriteria.size || result.criteria.map(CriterionScore::name).toSet() != expectedCriteria.toSet()) throw EvaluationException("evaluation must contain all expected criteria exactly once")
        result.criteria.forEach { criterion ->
            if (criterion.score !in 1..5) throw EvaluationException("evaluation criterion score must be between 1 and 5")
            if (criterion.comment.isBlank() || criterion.evidence.isBlank()) throw EvaluationException("evaluation criterion text must not be empty")
        }
    }

    private fun cleanJson(raw: String) = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    @Serializable private data class EvaluationPayload(val overallScore: Int, val summary: String, val recommendations: List<String>, val criteria: List<CriterionPayload>)
    @Serializable private data class CriterionPayload(val name: String, val score: Int, val comment: String, val evidence: String)
    private data class EvaluationResult(val overallScore: Int, val summary: String, val recommendations: List<String>, val criteria: List<CriterionScore>)
}
