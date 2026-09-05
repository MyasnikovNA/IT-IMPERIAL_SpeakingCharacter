package ru.itimperial.speakingcharacter.service

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.itimperial.speakingcharacter.llm.LlmClient
import ru.itimperial.speakingcharacter.model.MessageRole
import ru.itimperial.speakingcharacter.model.TrainingMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверяет строгий JSON-контракт итоговой оценки до сохранения session aggregate. */
class ReportServiceTest {
    private val criteria = listOf("Критерий A", "Критерий B", "Критерий C")

    /** Принимает валидный отчёт с точным набором критериев. */
    @Test
    fun `build accepts valid dynamic criteria`() = runTest {
        val report = service(validJson()).build(messages(), criteria)

        assertEquals(4, report.overallScore)
        assertEquals(criteria, report.criteria.map { it.name })
        assertEquals("EVALUATED", report.evaluationStatus)
    }

    /** Отклоняет score вне допустимого диапазона вместо неявного исправления значения. */
    @Test
    fun `build rejects out of range score`() = runTest {
        assertEvaluationFails(validJson().replace("\"overallScore\":4", "\"overallScore\":6"))
    }

    /** Отклоняет отсутствующий ожидаемый критерий. */
    @Test
    fun `build rejects missing criterion`() = runTest {
        assertEvaluationFails(validJson().replace("Критерий C", "Другой критерий"))
    }

    /** Отклоняет невалидный JSON, не создавая fallback-отчёт. */
    @Test
    fun `build rejects malformed json`() = runTest {
        assertEvaluationFails("not-json")
    }

    private fun service(response: String) = ReportService(
        llmClient = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String) = emptyFlow<String>()
            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean) = response
        },
        json = Json { ignoreUnknownKeys = true },
    )

    private fun messages() = listOf(TrainingMessage(MessageRole.USER, "Мой ответ", 1, "2026-01-01T00:00:00Z"))

    private suspend fun assertEvaluationFails(response: String) {
        try {
            service(response).build(messages(), criteria)
            error("Expected EvaluationException")
        } catch (_: EvaluationException) {
            // Ожидаемая строгая validation-ошибка.
        }
    }

    private fun validJson() = """
        {"overallScore":4,"summary":"Хорошая тренировка","recommendations":["Добавьте конкретный пример"],"criteria":[
        {"name":"Критерий A","score":4,"comment":"Комментарий","evidence":"Факт"},
        {"name":"Критерий B","score":5,"comment":"Комментарий","evidence":"Факт"},
        {"name":"Критерий C","score":3,"comment":"Комментарий","evidence":"Факт"}]}
    """.trimIndent()
}
