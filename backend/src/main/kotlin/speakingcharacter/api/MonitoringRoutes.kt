/** Принимает безопасные browser latency-метрики для корреляции с backend turn. */
package speakingcharacter.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import org.slf4j.LoggerFactory

private val monitoringLogger = LoggerFactory.getLogger("MonitoringRoutes")
private const val MAX_REPORTED_LATENCY_MS = 10 * 60 * 1_000L
private const val MAX_REPORTED_METRICS = 20
private val metricNamePattern = Regex("[a-z][a-z0-9_]{0,63}")

/** Регистрирует endpoint приёма измерений browser участка без сохранения пользовательских данных. */
fun Application.registerMonitoringRoutes() {
    routing {
        post("/api/metrics") {
            val request = call.receive<BrowserLatencyMetricsRequest>()
            if (runCatching { UUID.fromString(request.turnId) }.isFailure ||
                request.outcome.isBlank() ||
                request.metrics.size > MAX_REPORTED_METRICS ||
                request.slo.size > MAX_REPORTED_METRICS ||
                request.metrics.any { (name, value) -> !metricNamePattern.matches(name) || value !in 0..MAX_REPORTED_LATENCY_MS } ||
                request.slo.keys.any { name -> !metricNamePattern.matches(name) }
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid latency metrics"))
                return@post
            }
            monitoringLogger.info(
                "browser_latency_summary turnId={} sessionId={} outcome={} metrics={} slo={}",
                request.turnId,
                request.sessionId,
                request.outcome,
                request.metrics.toSortedMap(),
                request.slo.toSortedMap(),
            )
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
