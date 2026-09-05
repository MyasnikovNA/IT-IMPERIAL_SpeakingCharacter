/** Определяет узкую границу inference, общую для Gemini и unit-тестов. */
package speakingcharacter.service

import speakingcharacter.model.LlmMessage

/** Выполняет текстовую и structured JSON генерацию без знания persistence. */
interface LlmClient {
    /** Генерирует краткий текстовый ответ персонажа для обычного хода диалога. */
    suspend fun generate(systemPrompt: String, messages: List<LlmMessage>): String

    /** Генерирует JSON-ответ для итоговой оценки тренировки. */
    suspend fun generateStructuredJson(systemPrompt: String, messages: List<LlmMessage>): String
}
