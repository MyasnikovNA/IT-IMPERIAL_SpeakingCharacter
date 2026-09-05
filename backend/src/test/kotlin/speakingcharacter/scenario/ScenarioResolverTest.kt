/** Проверяет выбор preset, custom и свободной тренировки без обращения к БД. */
package speakingcharacter.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Тестирует преобразование transport-выбора в сохранённый сценарный snapshot. */
class ScenarioResolverTest {
    private val resolver = ScenarioResolver(ScenarioCatalog())

    /** Оставляет snapshot пустым, когда сотрудник начинает свободный диалог. */
    @Test
    fun `resolve accepts absent selection for free conversation`() {
        assertNull(resolver.resolve(null))
    }

    /** Разрешает только встроенный preset и помечает его источник. */
    @Test
    fun `resolve creates preset snapshot`() {
        val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(presetId = "sales-discovery")))

        assertEquals(ScenarioSource.PRESET, snapshot.source)
        assertEquals("sales-discovery", snapshot.definition.id)
    }

    /** Нормализует загруженный Markdown без добавления его в общий каталог. */
    @Test
    fun `resolve creates uploaded snapshot`() {
        val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(markdown = customScenarioMarkdown())))

        assertEquals(ScenarioSource.UPLOADED, snapshot.source)
        assertEquals("custom-training", snapshot.definition.id)
    }

    /** Отклоняет неоднозначный выбор до создания тренировочной сессии. */
    @Test
    fun `resolve rejects simultaneous preset and markdown`() {
        assertFailsWith<ScenarioSelectionException> {
            resolver.resolve(ScenarioSelection("sales-discovery", customScenarioMarkdown()))
        }
    }

    /** Создаёт минимальный допустимый Markdown custom-сценария. */
    private fun customScenarioMarkdown(): String = """
        <!-- scenario-meta
        {"id":"custom-training","version":1,"title":"Пользовательская тренировка","criteria":["Понятность ответа"],"stages":[{"id":"start","goal":"Начать разговор","exitRule":"continue"},{"id":"finish","goal":"Подвести итог","exitRule":"complete"}]}
        -->
        Проведи короткую тренировку и помоги сотруднику сформулировать уверенный ответ.
    """.trimIndent()
}
