/** Проверяет публичный каталог сценариев без подключения базы данных и LLM. */
package speakingcharacter.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
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

    /** Проверяет Markdown до старта тренировки, но не добавляет его в preset-каталог. */
    @Test
    fun `validate endpoint accepts valid uploaded scenario`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            registerScenarioRoutes(ScenarioCatalog())
        }

        val response = client.post("/api/scenarios/validate") {
            setBody(
                TextContent(
                    Json.encodeToString(ScenarioValidationRequest(customScenarioMarkdown())),
                    ContentType.Application.Json,
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("custom-training", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
    }

    /** Создаёт минимальный допустимый Markdown custom-сценария. */
    private fun customScenarioMarkdown(): String = """
        <!-- scenario-meta
        {"id":"custom-training","version":1,"title":"Пользовательская тренировка","criteria":["Понятность ответа"],"stages":[{"id":"start","goal":"Начать разговор","exitRule":"continue"},{"id":"finish","goal":"Подвести итог","exitRule":"complete"}]}
        -->
        Проведи короткую тренировку и помоги сотруднику сформулировать уверенный ответ.
    """.trimIndent()
}
