/** Описывает публичные JSON-представления каталога тренировочных сценариев. */
package speakingcharacter.api

import kotlinx.serialization.Serializable
import speakingcharacter.scenario.ScenarioSelection

/** Краткая безопасная карточка сценария, доступная интерфейсу выбора. */
@Serializable
data class ScenarioSummaryDto(
    val id: String,
    val version: Int,
    val title: String,
    val criteria: List<String>,
    val stages: List<ScenarioStageDto>,
)

/** Метаданные одного этапа, которые помогают сотруднику понять ход тренировки. */
@Serializable
data class ScenarioStageDto(
    val id: String,
    val goal: String,
)

/** Необязательный выбор preset либо загруженного Markdown для новой тренировочной сессии. */
@Serializable
data class ScenarioSelectionRequest(val presetId: String? = null, val markdown: String? = null)

/** Тело предварительной проверки custom Markdown-сценария без его сохранения. */
@Serializable
data class ScenarioValidationRequest(val markdown: String)

/** Преобразует transport-форму выбора в независимую domain-модель. */
internal fun ScenarioSelectionRequest.toDomainSelection(): ScenarioSelection = ScenarioSelection(presetId, markdown)
