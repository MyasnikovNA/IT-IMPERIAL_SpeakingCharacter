/** Реализует хранение сессий и сообщений через прямой JDBC. */
package speakingcharacter.db

import speakingcharacter.model.ChatMessage
import speakingcharacter.model.ChatRole
import speakingcharacter.model.SessionStatus
import speakingcharacter.model.TrainingSession
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import speakingcharacter.scenario.ScenarioSnapshot

/** JDBC-реализация минимального контракта истории тренировок. */
class JdbcChatRepository(private val dataSource: DataSource) : ChatRepository {
    private val json = Json { ignoreUnknownKeys = false }

    /** Создаёт активную сессию и сохраняет нормализованный snapshot выбранного сценария. */
    override fun createSession(scenarioSnapshot: ScenarioSnapshot?): TrainingSession {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO chat_sessions (id, scenario_id, scenario_snapshot) VALUES (?, ?, CAST(? AS jsonb))",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, scenarioSnapshot?.definition?.id)
                statement.setString(3, scenarioSnapshot?.let { json.encodeToString(ScenarioSnapshot.serializer(), it) })
                statement.executeUpdate()
            }
        }
        return TrainingSession(id, SessionStatus.ACTIVE, scenarioSnapshot?.definition?.id, null, scenarioSnapshot)
    }

    /** Читает состояние сессии, необходимое для orchestration. */
    override fun findSession(sessionId: UUID): TrainingSession? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status, scenario_id, finished_at, scenario_snapshot FROM chat_sessions WHERE id = ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@use null
                TrainingSession(
                    id = sessionId,
                    status = SessionStatus.valueOf(results.getString("status")),
                    scenarioId = results.getString("scenario_id"),
                    finishedAt = results.getTimestamp("finished_at")?.toInstant(),
                    scenarioSnapshot = results.getString("scenario_snapshot")?.let { snapshot ->
                        json.decodeFromString(ScenarioSnapshot.serializer(), snapshot)
                    },
                )
            }
        }
    }

    /** Сохраняет сообщение в истории сессии. */
    override fun addMessage(sessionId: UUID, role: ChatRole, content: String) {
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

    /** Выбирает ограниченное окно сообщений и возвращает его в прямом порядке. */
    override fun recentMessages(sessionId: UUID, limit: Int): List<ChatMessage> = queryMessages(
        "SELECT role, content, created_at FROM (SELECT role, content, created_at, id FROM chat_messages " +
            "WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ?) recent ORDER BY created_at ASC, id ASC",
        sessionId,
        limit,
    )

    /** Читает полный transcript сессии. */
    override fun history(sessionId: UUID): List<ChatMessage> = queryMessages(
        "SELECT role, content, created_at FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC, id ASC",
        sessionId,
        null,
    )

    /** Обновляет время последнего успешного хода. */
    override fun touchSession(sessionId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE chat_sessions SET updated_at = ? WHERE id = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now()))
                statement.setObject(2, sessionId)
                statement.executeUpdate()
            }
        }
    }

    /** Выполняет общий запрос истории с необязательным ограничением количества сообщений. */
    private fun queryMessages(sql: String, sessionId: UUID, limit: Int?): List<ChatMessage> = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, sessionId)
            if (limit != null) statement.setInt(2, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(ChatMessage(ChatRole.valueOf(results.getString("role")), results.getString("content"), results.getTimestamp("created_at").toInstant()))
                    }
                }
            }
        }
    }
}
