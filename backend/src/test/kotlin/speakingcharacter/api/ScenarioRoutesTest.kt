/** Проверяет публичный каталог сценариев без подключения базы данных и LLM. */
package speakingcharacter.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import speakingcharacter.scenario.ScenarioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Тестирует контракт списка, необходимого интерфейсу выбора сценария. */
class ScenarioRoutesTest {
    /** Возвращает все preset-сценарии, но не отдаёт внутренние инструкции Gemini. */
    @Test
    fun `catalog endpoint returns five safe scenario summaries`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            registerScenarioRoutes(ScenarioCatalog())
        }

        val response = client.get("/api/scenarios")
        val responseBody = response.bodyAsText()
        val scenarios = Json.parseToJsonElement(responseBody).jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(5, scenarios.size)
        assertEquals(
            setOf(
                "manager-feedback",
                "policy-knowledge",
                "sales-discovery",
                "sales-objection",
                "structured-interview",
            ),
            scenarios.map { scenario -> scenario.jsonObject.getValue("id").jsonPrimitive.content }.toSet(),
        )
        assertFalse(responseBody.contains("instructions"))
    }
}
