package ru.itimperial.speakingcharacter.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import ru.itimperial.speakingcharacter.model.ErrorResponse
import ru.itimperial.speakingcharacter.model.ScenarioStageDto
import ru.itimperial.speakingcharacter.model.ScenarioSummaryDto
import ru.itimperial.speakingcharacter.model.ScenarioValidationRequest
import ru.itimperial.speakingcharacter.scenario.ScenarioCatalog
import ru.itimperial.speakingcharacter.scenario.ScenarioDefinition
import ru.itimperial.speakingcharacter.scenario.ScenarioResolver
import ru.itimperial.speakingcharacter.scenario.ScenarioSelection
import ru.itimperial.speakingcharacter.scenario.ScenarioSelectionException

/** Регистрирует read-only API каталога и проверку одноразового Markdown-сценария. */
fun Application.configureScenarioRoutes() {
    val catalog by inject<ScenarioCatalog>()
    val resolver by inject<ScenarioResolver>()
    routing {
        get("/api/scenarios") { call.respond(catalog.all().map(ScenarioDefinition::toSummary)) }
        post("/api/scenarios/validate") {
            try {
                val markdown = call.receive<ScenarioValidationRequest>().markdown
                val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(markdown = markdown)))
                call.respond(snapshot.definition.toSummary())
            } catch (exception: ScenarioSelectionException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(exception.message ?: "invalid scenario"))
            }
        }
    }
}

/** Убирает внутренние инструкции методиста из публичного ответа каталога. */
private fun ScenarioDefinition.toSummary() = ScenarioSummaryDto(
    id = id,
    version = version,
    title = title,
    criteria = criteria,
    stages = stages.map { ScenarioStageDto(it.id, it.goal) },
)
