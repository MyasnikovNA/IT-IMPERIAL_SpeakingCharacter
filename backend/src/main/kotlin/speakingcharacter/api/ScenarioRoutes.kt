/** Публикует проверенный каталог сценариев без внутренних LLM-инструкций. */
package speakingcharacter.api

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import speakingcharacter.scenario.ScenarioCatalog
import speakingcharacter.scenario.ScenarioDefinition
import speakingcharacter.scenario.ScenarioResolver
import speakingcharacter.scenario.ScenarioSelectionException

/** Регистрирует read-only API для выбора предустановленной корпоративной тренировки. */
fun Application.registerScenarioRoutes(
    scenarioCatalog: ScenarioCatalog,
    scenarioResolver: ScenarioResolver = ScenarioResolver(scenarioCatalog),
) {
    routing {
        get("/api/scenarios") {
            call.respond(scenarioCatalog.all().map(ScenarioDefinition::toSummaryDto))
        }
        post("/api/scenarios/validate") {
            try {
                val selection = ScenarioSelectionRequest(markdown = call.receive<ScenarioValidationRequest>().markdown)
                val snapshot = scenarioResolver.resolve(selection.toDomainSelection())
                    ?: error("Custom-сценарий не может разрешиться в null")
                call.respond(snapshot.definition.toSummaryDto())
            } catch (exception: ScenarioSelectionException) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse(exception.message ?: "invalid scenario"))
            }
        }
    }
}

/** Преобразует внутренний сценарий в DTO, не раскрывая system-инструкции агенту. */
private fun ScenarioDefinition.toSummaryDto(): ScenarioSummaryDto = ScenarioSummaryDto(
    id = id,
    version = version,
    title = title,
    criteria = criteria,
    stages = stages.map { stage -> ScenarioStageDto(id = stage.id, goal = stage.goal) },
)
