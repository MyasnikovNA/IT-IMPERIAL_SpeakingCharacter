/** Проверяет строгий parser Markdown-сценариев. */
package speakingcharacter.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Проверяет JSON front matter, ограничения полей и текст инструкции сценария. */
class ScenarioParserTest {
    /** Разбирает корректный встроенный сценарий в domain-модель. */
    @Test
    fun `parse accepts valid markdown scenario`() {
        val definition = ScenarioParser().parse(validScenario())

        assertEquals("sales-demo", definition.id)
        assertEquals(2, definition.stages.size)
        assertEquals("Аватар проводит короткую тренировку с участником.", definition.instructions)
    }

    /** Отклоняет повторяющиеся идентификаторы этапов. */
    @Test
    fun `parse rejects duplicate stage identifiers`() {
        val markdown = validScenario().replace("\"close\"", "\"need\"")

        assertFailsWith<ScenarioValidationException> { ScenarioParser().parse(markdown) }
    }

    /** Отклоняет сценарий без корректного JSON front matter. */
    @Test
    fun `parse rejects malformed metadata`() {
        assertFailsWith<ScenarioValidationException> {
            ScenarioParser().parse("<!-- scenario-meta\n{invalid}\n-->\nИнструкция длиннее двадцати символов.")
        }
    }

    /** Создаёт минимальный корректный сценарий для проверки parser. */
    private fun validScenario(): String = """
        <!-- scenario-meta
        {
          "id": "sales-demo",
          "version": 1,
          "title": "Демонстрация продаж",
          "criteria": ["Выявление потребности"],
          "stages": [
            {"id": "need", "goal": "Уточнить задачу клиента", "exitRule": "need_confirmed"},
            {"id": "close", "goal": "Согласовать следующий шаг", "exitRule": "next_step_agreed"}
          ]
        }
        -->

        Аватар проводит короткую тренировку с участником.
    """.trimIndent()
}
