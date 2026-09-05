/** Определяет выбор и неизменяемый snapshot сценария для одной тренировочной сессии. */
package speakingcharacter.scenario

import kotlinx.serialization.Serializable

/** Необработанный выбор сценария, полученный при создании новой тренировки. */
data class ScenarioSelection(val presetId: String? = null, val markdown: String? = null)

/** Указывает, откуда пользователь выбрал сценарий для сохранённого snapshot. */
@Serializable
enum class ScenarioSource { PRESET, UPLOADED }

/** Нормализованная конфигурация сценария, закреплённая за одной сессией. */
@Serializable
data class ScenarioSnapshot(val source: ScenarioSource, val definition: ScenarioDefinition)

/** Разрешает пользовательский выбор только в проверенный неизменяемый snapshot. */
class ScenarioResolver(
    private val catalog: ScenarioCatalog,
    private val parser: ScenarioParser = ScenarioParser(),
) {
    /** Возвращает null для свободного диалога либо snapshot доступного preset/custom-сценария. */
    fun resolve(selection: ScenarioSelection?): ScenarioSnapshot? {
        if (selection == null) return null
        val presetId = selection.presetId?.trim()?.takeIf(String::isNotEmpty)
        val markdown = selection.markdown?.takeIf(String::isNotBlank)
        if ((presetId == null) == (markdown == null)) {
            throw ScenarioSelectionException("Выберите ровно один preset-сценарий или Markdown-файл")
        }
        if (presetId != null) {
            return ScenarioSnapshot(
                source = ScenarioSource.PRESET,
                definition = catalog.find(presetId)
                    ?: throw ScenarioSelectionException("Указанный preset-сценарий недоступен"),
            )
        }
        return ScenarioSnapshot(
            source = ScenarioSource.UPLOADED,
            definition = try {
                parser.parse(requireNotNull(markdown))
            } catch (exception: ScenarioValidationException) {
                throw ScenarioSelectionException(exception.message ?: "Markdown-сценарий не прошёл проверку")
            },
        )
    }
}

/** Сообщает, что выбор сценария нельзя безопасно применить к тренировке. */
class ScenarioSelectionException(message: String) : IllegalArgumentException(message)
