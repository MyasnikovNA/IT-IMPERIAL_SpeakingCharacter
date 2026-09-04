/** Проверяет строгий парсинг structured JSON итоговой оценки. */
package speakingcharacter.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Проверяет обязательные поля, диапазоны score и fixed criteria evaluation. */
class EvaluationParserTest {
    /** Парсит корректный JSON в domain result. */
    @Test
    fun `parse accepts valid evaluation JSON`() {
        val result = parseEvaluationResult(validJson())

        assertEquals(4, result.overallScore)
        assertEquals(3, result.criteria.size)
        assertEquals("Полнота ответа", result.criteria.first().name)
    }

    /** Отклоняет оценку выше максимального допустимого значения. */
    @Test
    fun `parse rejects score above five`() {
        assertFailsWith<EvaluationException> { parseEvaluationResult(validJson().replace("\"overallScore\":4", "\"overallScore\":6")) }
    }

    /** Отклоняет оценку ниже минимального допустимого значения. */
    @Test
    fun `parse rejects score below one`() {
        assertFailsWith<EvaluationException> { parseEvaluationResult(validJson().replace("\"score\":4", "\"score\":0")) }
    }

    /** Отклоняет JSON без всех трёх fixed criteria. */
    @Test
    fun `parse rejects missing criteria`() {
        val responseWithoutCriteria = """
            {"overallScore":4,"summary":"Итог","recommendations":["Совет"],"criteria":[]}
        """.trimIndent()

        assertFailsWith<EvaluationException> { parseEvaluationResult(responseWithoutCriteria) }
    }

    /** Отклоняет malformed JSON от upstream модели. */
    @Test
    fun `parse rejects malformed JSON`() {
        assertFailsWith<EvaluationException> { parseEvaluationResult("not-json") }
    }

    /** Создаёт корректный ответ Gemini для parser unit-тестов. */
    private fun validJson(): String = """
        {
          "overallScore":4,
          "summary":"Тренировка пройдена уверенно.",
          "recommendations":["Уточнять следующий шаг"],
          "criteria":[
            {"name":"Полнота ответа","score":4,"comment":"Все основные элементы есть.","evidence":"Пользователь назвал следующий шаг."},
            {"name":"Следование сценарию","score":5,"comment":"Диалог соответствует сценарию.","evidence":"Реплики последовательны."},
            {"name":"Качество коммуникации","score":4,"comment":"Тон корректный.","evidence":"Использованы вежливые формулировки."}
          ]
        }
    """.trimIndent()
}
