/** Проверяет преобразование persistent history в context Gemini. */
package speakingcharacter.service

import kotlin.test.Test
import kotlin.test.assertEquals
import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import java.time.Instant

/** Тестирует порядок, лимит, роли и отсутствие дублирования в context builder. */
class ConversationContextBuilderTest {
    private val builder = ConversationContextBuilder(3)

    /** Сохраняет исходный хронологический порядок сообщений. */
    @Test
    fun `build preserves chronological order`() {
        val context = builder.build(messages("1", "2", "3"))

        assertEquals(listOf("1", "2", "3"), context.map { it.content })
    }

    /** Оставляет только последние сообщения в пределах MAX_CONTEXT_MESSAGES. */
    @Test
    fun `build keeps last maximum number of messages`() {
        val context = builder.build(messages("1", "2", "3", "4", "5"))

        assertEquals(listOf("3", "4", "5"), context.map { it.content })
    }

    /** Преобразует внутренние роли в роли, ожидаемые Gemini. */
    @Test
    fun `build maps chat roles to Gemini roles`() {
        val context = builder.build(messages("1", "2"))

        assertEquals(listOf("user", "model"), context.map { it.role })
    }

    /** Не добавляет текущую user-реплику второй раз после получения из persistence. */
    @Test
    fun `build does not duplicate current user message`() {
        val context = builder.build(messages("Ранее", "Ответ", "Текущая реплика"))

        assertEquals(1, context.count { it.content == "Текущая реплика" })
    }

    /** Создаёт хронологические сообщения с чередующимися ролями. */
    private fun messages(vararg contents: String): List<ChatMessage> = contents.mapIndexed { index, content ->
        ChatMessage(
            role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            content = content,
            createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()),
        )
    }
}
