/** Проверяет безопасный приём агрегированных browser latency-метрик. */
package speakingcharacter.api

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Тестирует допустимые и некорректные telemetry payload без реального browser. */
class MonitoringRoutesTest {
    /** Принимает только агрегированные числа для валидного turn UUID. */
    @Test
    fun `metrics endpoint accepts valid browser latency report`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            registerMonitoringRoutes()
        }

        val response = client.post("/api/metrics") {
            setBody(
                TextContent(
                    """{"turnId":"00000000-0000-4000-8000-000000000001","outcome":"completed","metrics":{"simli_speaking":1200},"slo":{"first_response_audio":true}}""",
                    ContentType.Application.Json,
                ),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    /** Отклоняет отрицательную latency до structured logging. */
    @Test
    fun `metrics endpoint rejects invalid latency report`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            registerMonitoringRoutes()
        }

        val response = client.post("/api/metrics") {
            setBody(
                TextContent(
                    """{"turnId":"not-a-uuid","outcome":"completed","metrics":{"message":-1},"slo":{}}""",
                    ContentType.Application.Json,
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
