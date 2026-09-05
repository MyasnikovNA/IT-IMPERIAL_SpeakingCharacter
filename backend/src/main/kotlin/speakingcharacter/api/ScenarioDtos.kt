/** Описывает публичные JSON-представления каталога тренировочных сценариев. */
package speakingcharacter.api

import kotlinx.serialization.Serializable

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
