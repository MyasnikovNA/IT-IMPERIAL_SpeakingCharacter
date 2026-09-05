/** Проверяет загрузку всех встроенных сценариев из classpath. */
package speakingcharacter.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Проверяет полноту preset-каталога для демонстрационных тренировок. */
class ScenarioCatalogTest {
    /** Загружает ровно пять валидных сценариев с устойчивыми идентификаторами. */
    @Test
    fun `catalog loads five built in scenarios`() {
        val catalog = ScenarioCatalog()

        assertEquals(
            setOf(
                "sales-discovery",
                "sales-objection",
                "structured-interview",
                "policy-knowledge",
                "manager-feedback",
            ),
            catalog.all().map(ScenarioDefinition::id).toSet(),
        )
        assertNotNull(catalog.find("sales-discovery"))
        assertEquals(null, catalog.find("unknown"))
    }
}
