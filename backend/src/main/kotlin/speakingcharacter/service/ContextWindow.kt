/** Формирует ограниченный хронологический контекст для языковой модели. */
package speakingcharacter.service

import speakingcharacter.db.ChatMessage

/** Выбирает сохранённое окно сообщений, используемое как история диалога Gemini. */
object ContextWindow {
    /**
     * Возвращает ограниченный набор последних сообщений, не добавляя текущую реплику повторно.
     *
     * @param persistedMessages уже сохранённые сообщения в хронологическом порядке
     * @param maxMessages максимальное число сообщений в запросе к LLM
     */
    fun select(persistedMessages: List<ChatMessage>, maxMessages: Int): List<ChatMessage> {
        require(maxMessages > 0) { "maxMessages must be positive" }
        return persistedMessages.takeLast(maxMessages)
    }
}
