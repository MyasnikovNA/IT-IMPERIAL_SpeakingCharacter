/** Загружает встроенные сценарии из classpath один раз при запуске приложения. */
package speakingcharacter.scenario

/** Предоставляет неизменяемый каталог проверенных сценариев для будущих API и сервисов. */
class ScenarioCatalog(
    private val parser: ScenarioParser = ScenarioParser(),
    private val resourceReader: (String) -> String = { path -> loadResource(path) },
) {
    private val scenarios: Map<String, ScenarioDefinition> = PRESET_RESOURCES
        .map { path -> parser.parse(resourceReader(path)) }
        .also { definitions ->
            if (definitions.map(ScenarioDefinition::id).toSet().size != definitions.size) {
                throw ScenarioValidationException("Встроенные сценарии не должны иметь одинаковые идентификаторы")
            }
        }
        .associateBy(ScenarioDefinition::id)

    /** Возвращает все встроенные сценарии в стабильном порядке отображения. */
    fun all(): List<ScenarioDefinition> = scenarios.values.sortedBy(ScenarioDefinition::title)

    /** Находит встроенный сценарий по его стабильному идентификатору. */
    fun find(id: String): ScenarioDefinition? = scenarios[id]

    private companion object {
        val PRESET_RESOURCES = listOf(
            "scenarios/sales-discovery.md",
            "scenarios/sales-objection.md",
            "scenarios/structured-interview.md",
            "scenarios/policy-knowledge.md",
            "scenarios/manager-feedback.md",
        )

        /** Читает обязательный непустой ресурс сценария из classpath. */
        fun loadResource(path: String): String = ScenarioCatalog::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Ресурс сценария отсутствует или пуст: $path")
    }
}
