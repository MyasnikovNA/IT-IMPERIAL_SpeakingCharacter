/** Описывает неизменяемые модели встроенного сценария корпоративной тренировки. */
package speakingcharacter.scenario

/** Полное описание сценария, безопасно загруженного из Markdown-ресурса. */
data class ScenarioDefinition(
    val id: String,
    val version: Int,
    val title: String,
    val criteria: List<String>,
    val stages: List<ScenarioStage>,
    val instructions: String,
)

/** Один последовательный этап, через который аватар проводит участника. */
data class ScenarioStage(
    val id: String,
    val goal: String,
    val exitRule: String,
)

/** Сообщает, что Markdown-сценарий не соответствует безопасному контракту. */
class ScenarioValidationException(message: String) : IllegalArgumentException(message)
