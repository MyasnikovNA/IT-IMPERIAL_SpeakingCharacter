/** Предоставляет небольшой набор JDBC-операций для постоянной памяти диалога. */
package speakingcharacter.db

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Допустимые роли, сохраняемые в диалоге. */
enum class ChatRole { USER, ASSISTANT }

/** Сообщение диалога с меткой времени. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val createdAt: Instant,
)

/** Прямой JDBC-репозиторий сессий и их сообщений. */
class ChatRepository(private val dataSource: DataSource) {
    /** Создаёт пустую сессию с новым идентификатором и возвращает его. */
    fun createSession(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO chat_sessions (id) VALUES (?)").use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        return id
    }

    /** Проверяет существование сессии. */
    fun sessionExists(sessionId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM chat_sessions WHERE id = ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { results -> results.next() }
        }
    }

    /** Сохраняет одно сообщение пользователя или ассистента. */
    fun addMessage(sessionId: UUID, role: ChatRole, content: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO chat_messages (id, session_id, role, content) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, sessionId)
                statement.setString(3, role.name)
                statement.setString(4, content)
                statement.executeUpdate()
            }
        }
    }

    /** Возвращает последние сообщения, сохраняя их хронологический порядок. */
    fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT role, content, created_at FROM (" +
                "SELECT role, content, created_at FROM chat_messages WHERE session_id = ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?" +
                ") recent ORDER BY created_at ASC",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setInt(2, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            ChatMessage(
                                role = ChatRole.valueOf(results.getString("role")),
                                content = results.getString("content"),
                                createdAt = results.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Возвращает все сообщения в хронологическом порядке для диагностического API. */
    fun history(sessionId: UUID): List<ChatMessage> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT role, content, created_at FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC, id ASC",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            ChatMessage(
                                role = ChatRole.valueOf(results.getString("role")),
                                content = results.getString("content"),
                                createdAt = results.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Обновляет метку времени сессии после готового ответа ассистента. */
    fun touchSession(sessionId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE chat_sessions SET updated_at = ? WHERE id = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now()))
                statement.setObject(2, sessionId)
                statement.executeUpdate()
            }
        }
    }
}
