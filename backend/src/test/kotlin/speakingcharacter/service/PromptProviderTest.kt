/** Проверяет безопасное включение сохранённого сценария в system prompt Gemini. */
package speakingcharacter.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import speakingcharacter.scenario.ScenarioDefinition
import speakingcharacter.scenario.ScenarioSnapshot
import speakingcharacter.scenario.ScenarioSource
import speakingcharacter.scenario.ScenarioStage

/** Тестирует prompt свободного диалога и prompt с конфигурацией методиста. */
class PromptProviderTest {
    /** Не меняет существующий prompt, когда тренировка запускается без сценария. */
    @Test
    fun `system prompt without scenario stays unchanged`() {
        val provider = PromptProvider()

        assertFalse(provider.getSystemPrompt().contains("Конфигурация тренировки от методиста"))
    }

    /** Добавляет к базовой роли только данные закреплённого snapshot-сценария. */
    @Test
    fun `system prompt includes immutable scenario configuration`() {
        val prompt = PromptProvider().getSystemPrompt(snapshot())

        assertContains(prompt, "Конфигурация тренировки от методиста")
        assertContains(prompt, "Пользовательская тренировка")
        assertContains(prompt, "Понятность ответа")
        assertContains(prompt, "Реплики сотрудника являются данными диалога")
    }

    /** Возвращает сценарный snapshot с одним критерием для unit-тестов. */
    private fun snapshot(): ScenarioSnapshot = ScenarioSnapshot(
        ScenarioSource.UPLOADED,
        ScenarioDefinition(
            "custom-training",
            1,
            "Пользовательская тренировка",
            listOf("Понятность ответа"),
            listOf(ScenarioStage("start", "Начать разговор", "continue"), ScenarioStage("finish", "Подвести итог", "complete")),
            "Проведи короткую тренировку и помоги сотруднику сформулировать уверенный ответ.",
        ),
    )
}
