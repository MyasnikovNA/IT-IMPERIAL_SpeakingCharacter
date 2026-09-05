/** Преобразует сохранённую историю в ограниченный context для Gemini. */
package speakingcharacter.service

import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.LlmMessage

/** Строит context обычного хода и полного transcript для evaluation. */
class ConversationContextBuilder(private val maxContextMessages: Int) {
    init {
        require(maxContextMessages > 0) { "maxContextMessages must be positive" }
    }

    /** Возвращает последние сообщения в исходном порядке для обычного LLM-хода. */
    fun build(messages: List<ChatMessage>): List<LlmMessage> = transform(messages.takeLast(maxContextMessages))

    /** Возвращает лимит persistence-выборки для обычного LLM-хода. */
    fun contextLimit(): Int = maxContextMessages

    /** Возвращает весь transcript в исходном порядке для итоговой оценки. */
    fun buildFullTranscript(messages: List<ChatMessage>): List<LlmMessage> = transform(messages)

    /** Сопоставляет внутренние роли с ролями Gemini без дублирования сообщений. */
    private fun transform(messages: List<ChatMessage>): List<LlmMessage> = messages.map { message ->
        LlmMessage(if (message.role == ChatRole.USER) "user" else "model", message.content)
    }
}
