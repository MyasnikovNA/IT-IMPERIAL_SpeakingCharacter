/** Проверяет, что текущая реплика пользователя попадает в ограниченный LLM-контекст один раз. */
package speakingcharacter.service

import kotlin.test.Test
import kotlin.test.assertEquals
import speakingcharacter.db.ChatMessage
import speakingcharacter.db.ChatRole
import java.time.Instant

/** Тестирует выбор контекста независимо от PostgreSQL и Gemini. */
class ContextWindowTest {
    /** Оставляет сохранённую текущую реплику пользователя один раз, не добавляя её повторно. */
    @Test
    fun `select does not duplicate current user message`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val messages = listOf(
            ChatMessage(ChatRole.USER, "Earlier", now),
            ChatMessage(ChatRole.ASSISTANT, "Earlier answer", now.plusSeconds(1)),
            ChatMessage(ChatRole.USER, "Current message", now.plusSeconds(2)),
        )

        val context = ContextWindow.select(messages, 20)

        assertEquals(1, context.count { it.content == "Current message" })
        assertEquals(messages, context)
    }

    /** Сохраняет хронологический порядок сообщений в окне контекста. */
    @Test
    fun `select preserves chronological order`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val messages = listOf(
            ChatMessage(ChatRole.USER, "1", now),
            ChatMessage(ChatRole.ASSISTANT, "2", now.plusSeconds(1)),
            ChatMessage(ChatRole.USER, "3", now.plusSeconds(2)),
        )

        assertEquals(messages, ContextWindow.select(messages, 3))
    }

    /** Ограничивает окно последними сообщениями, сохраняя их хронологический порядок. */
    @Test
    fun `select keeps last maximum number of messages`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val messages = listOf(
            ChatMessage(ChatRole.USER, "1", now),
            ChatMessage(ChatRole.ASSISTANT, "2", now.plusSeconds(1)),
            ChatMessage(ChatRole.USER, "3", now.plusSeconds(2)),
            ChatMessage(ChatRole.ASSISTANT, "4", now.plusSeconds(3)),
            ChatMessage(ChatRole.USER, "5", now.plusSeconds(4)),
        )

        val context = ContextWindow.select(messages, 3)

        assertEquals(listOf("3", "4", "5"), context.map { it.content })
    }
}
