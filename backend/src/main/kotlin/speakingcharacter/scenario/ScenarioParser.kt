/** Разбирает и строго валидирует front matter встроенных Markdown-сценариев. */
package speakingcharacter.scenario

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Преобразует статичный Markdown-файл в безопасное domain-описание сценария. */
class ScenarioParser {
    private val json = Json { ignoreUnknownKeys = false }

    /** Разбирает JSON front matter и инструкцию сценария, отклоняя некорректные данные. */
    fun parse(markdown: String): ScenarioDefinition {
        if (markdown.length > MAX_MARKDOWN_LENGTH) {
            throw ScenarioValidationException("Сценарий превышает допустимый размер")
        }

        val match = frontMatterPattern.matchEntire(markdown)
            ?: throw ScenarioValidationException("В сценарии отсутствует корректный scenario-meta блок")
        val metadata = try {
            json.decodeFromString(ScenarioMetadata.serializer(), match.groupValues[1])
        } catch (exception: Exception) {
            throw ScenarioValidationException("Не удалось разобрать scenario-meta: ${exception.message}")
        }
        val definition = ScenarioDefinition(
            id = metadata.id.trim(),
            version = metadata.version,
            title = metadata.title.trim(),
            criteria = metadata.criteria.map(String::trim),
            stages = metadata.stages.map {
                ScenarioStage(it.id.trim(), it.goal.trim(), it.exitRule.trim())
            },
            instructions = match.groupValues[2].trim(),
        )

        validate(definition)
        return definition
    }

    /** Проверяет ограниченный контракт сценария до его использования в LLM prompt. */
    private fun validate(definition: ScenarioDefinition) {
        requireId(definition.id, "Идентификатор сценария")
        if (definition.version < 1) {
            throw ScenarioValidationException("Версия сценария должна быть положительной")
        }
        requireText(definition.title, "Название сценария", 3, 120)
        requireListSize(definition.criteria, "Критерии", 1, 6)
        requireUnique(definition.criteria, "Критерии")
        definition.criteria.forEach { requireText(it, "Критерий", 3, 120) }

        requireListSize(definition.stages, "Этапы", 2, 5)
        requireUnique(definition.stages.map(ScenarioStage::id), "Идентификаторы этапов")
        definition.stages.forEach { stage ->
            requireId(stage.id, "Идентификатор этапа")
            requireText(stage.goal, "Цель этапа", 3, 300)
            if (!EXIT_RULE_PATTERN.matches(stage.exitRule)) {
                throw ScenarioValidationException("Некорректное правило выхода этапа: ${stage.exitRule}")
            }
        }
        requireText(definition.instructions, "Инструкция сценария", 20, 6_000)
    }

    /** Проверяет формат стабильного идентификатора сценария или этапа. */
    private fun requireId(value: String, field: String) {
        if (!ID_PATTERN.matches(value)) {
            throw ScenarioValidationException("$field имеет некорректный формат")
        }
    }

    /** Проверяет длину обязательного текстового поля. */
    private fun requireText(value: String, field: String, minLength: Int, maxLength: Int) {
        if (value.length !in minLength..maxLength) {
            throw ScenarioValidationException("$field должно содержать от $minLength до $maxLength символов")
        }
    }

    /** Проверяет допустимое количество элементов в сценарном списке. */
    private fun <T> requireListSize(values: List<T>, field: String, minSize: Int, maxSize: Int) {
        if (values.size !in minSize..maxSize) {
            throw ScenarioValidationException("$field должны содержать от $minSize до $maxSize элементов")
        }
    }

    /** Отклоняет повторяющиеся значения без учёта регистра. */
    private fun requireUnique(values: List<String>, field: String) {
        if (values.map { it.lowercase() }.toSet().size != values.size) {
            throw ScenarioValidationException("$field не должны повторяться")
        }
    }

    /** Описывает JSON front matter до преобразования в domain-модель. */
    @Serializable
    private data class ScenarioMetadata(
        val id: String,
        val version: Int,
        val title: String,
        val criteria: List<String>,
        val stages: List<ScenarioStageMetadata>,
    )

    /** Описывает JSON-этап до его валидации. */
    @Serializable
    private data class ScenarioStageMetadata(
        val id: String,
        val goal: String,
        val exitRule: String,
    )

    private companion object {
        const val MAX_MARKDOWN_LENGTH = 32_000
        val ID_PATTERN = Regex("[a-z][a-z0-9-]{1,62}")
        val EXIT_RULE_PATTERN = Regex("[a-z][a-z0-9_]{1,62}")
        val frontMatterPattern = Regex(
            """\A\s*<!--\s*scenario-meta\s*\r?\n(.*?)\r?\n-->\s*(.*)\z""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
