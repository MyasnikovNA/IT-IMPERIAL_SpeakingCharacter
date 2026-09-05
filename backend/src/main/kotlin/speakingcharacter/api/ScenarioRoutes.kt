/** Публикует проверенный каталог сценариев без внутренних LLM-инструкций. */
package speakingcharacter.api

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import speakingcharacter.scenario.ScenarioCatalog
import speakingcharacter.scenario.ScenarioDefinition

/** Регистрирует read-only API для выбора предустановленной корпоративной тренировки. */
fun Application.registerScenarioRoutes(scenarioCatalog: ScenarioCatalog) {
    routing {
        get("/api/scenarios") {
            call.respond(scenarioCatalog.all().map(ScenarioDefinition::toSummaryDto))
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
