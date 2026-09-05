package ru.itimperial.speakingcharacter.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.llm.LlmClient
import ru.itimperial.speakingcharacter.model.CriterionScore
import ru.itimperial.speakingcharacter.model.MessageRole
import ru.itimperial.speakingcharacter.model.TrainingMessage
import ru.itimperial.speakingcharacter.model.TrainingReport
import java.time.Instant

class ReportService(
    private val llmClient: LlmClient,
    private val appConfig: AppConfig,
    private val json: Json,
) {
    suspend fun build(
        messages: List<TrainingMessage>,
        sessionCriteria: String? = null,
    ): TrainingReport {
        if (messages.isEmpty()) {
            return TrainingReport(
                generatedAt = Instant.now().toString(),
                summary = "Диалог не состоялся.",
                evaluationStatus = "EMPTY_DIALOGUE",
            )
        }

        val criteria = sessionCriteria ?: appConfig.trainingCriteria
        val transcript = messages.joinToString("\n") { message ->
            val role = if (message.role == MessageRole.USER) "Сотрудник" else "AI-тренер"
            val interrupted = if (message.interrupted) " [прервано]" else ""
            "$role$interrupted: ${message.text}"
        }

        val systemPrompt = if (criteria == null) {
            "Сформируй краткий итог корпоративной тренировки по стенограмме. " +
                "Не выставляй числовые оценки, потому что критерии оценки не заданы. " +
                "Верни только JSON согласно указанному формату."
        } else {
            "Ты методист, оценивающий корпоративную тренировку. " +
                "Используй только заданные критерии, не придумывай дополнительные. " +
                "Верни только JSON согласно указанному формату.\nКритерии:\n$criteria"
        }

        val prompt = """
            Проанализируй стенограмму ниже.

            Верни JSON вида:
            {
              "summary": "краткий итог",
              "overallScore": 0.0,
              "criteria": [{"name":"критерий","score":0.0,"comment":"комментарий"}],
              "mistakes": ["ошибка"],
              "recommendations": ["рекомендация"]
            }

            Если критерии не заданы, overallScore должен быть null, а criteria — пустым массивом.
            Если критерии заданы, score используй в диапазоне 0..10.

            Стенограмма:
            $transcript
        """.trimIndent()

        return try {
            val raw = llmClient.generateText(prompt, systemPrompt, jsonMode = true)
            val parsed = json.decodeFromString(ReportPayload.serializer(), cleanJson(raw))
            TrainingReport(
                generatedAt = Instant.now().toString(),
                summary = parsed.summary,
                overallScore = if (criteria == null) null else parsed.overallScore?.coerceIn(0.0, 10.0),
                criteria = if (criteria == null) emptyList() else parsed.criteria.map {
                    CriterionScore(
                        name = it.name,
                        score = it.score?.coerceIn(0.0, 10.0),
                        comment = it.comment,
                    )
                },
                mistakes = parsed.mistakes,
                recommendations = parsed.recommendations,
                evaluationStatus = if (criteria == null) "CRITERIA_NOT_CONFIGURED" else "EVALUATED",
            )
        } catch (_: Exception) {
            TrainingReport(
                generatedAt = Instant.now().toString(),
                summary = "Диалог завершён. Автоматический отчёт временно не сформирован.",
                evaluationStatus = "REPORT_GENERATION_FAILED",
            )
        }
    }

    private fun cleanJson(raw: String): String = raw
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    @Serializable
    private data class ReportPayload(
        val summary: String,
        val overallScore: Double? = null,
        val criteria: List<CriterionPayload> = emptyList(),
        val mistakes: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
    )

    @Serializable
    private data class CriterionPayload(
        val name: String,
        val score: Double? = null,
        val comment: String = "",
    )
}
