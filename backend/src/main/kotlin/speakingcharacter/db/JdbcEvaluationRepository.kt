/** Сохраняет и загружает отчёты тренировки через JDBC без ORM. */
package speakingcharacter.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import speakingcharacter.model.EvaluationCriterion
import speakingcharacter.model.EvaluationResult
import speakingcharacter.model.TrainingReport
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** JDBC-реализация атомарного persistence итоговой оценки. */
class JdbcEvaluationRepository(private val dataSource: DataSource) : EvaluationRepository {
    private val json = Json

    /** Читает report и его criteria для сессии в хронологическом порядке вставки. */
    override fun findReport(sessionId: UUID): TrainingReport? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT id, overall_score, summary, recommendations, created_at FROM training_reports WHERE session_id = ?",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@use null
                val reportId = results.getObject("id", UUID::class.java)
                TrainingReport(
                    id = reportId,
                    sessionId = sessionId,
                    result = EvaluationResult(
                        overallScore = results.getInt("overall_score"),
                        summary = results.getString("summary"),
                        recommendations = json.decodeFromString(results.getString("recommendations")),
                        criteria = findCriteria(connection, reportId),
                    ),
                    createdAt = results.getTimestamp("created_at").toInstant(),
                )
            }
        }
    }

    /** Атомарно сохраняет report и переводит сессию в FINISHED после успешной evaluation. */
    override fun saveReportAndFinishSession(report: TrainingReport): TrainingReport = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            insertReport(connection, report)
            insertCriteria(connection, report)
            connection.prepareStatement(
                "UPDATE chat_sessions SET status = 'FINISHED', finished_at = NOW(), updated_at = NOW() " +
                    "WHERE id = ? AND status = 'ACTIVE'",
            ).use { statement ->
                statement.setObject(1, report.sessionId)
                check(statement.executeUpdate() == 1) { "training session cannot be finished" }
            }
            connection.commit()
            report
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    /** Вставляет основную строку report с JSONB recommendations. */
    private fun insertReport(connection: Connection, report: TrainingReport) {
        connection.prepareStatement(
            "INSERT INTO training_reports (id, session_id, overall_score, summary, recommendations, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, report.id)
            statement.setObject(2, report.sessionId)
            statement.setInt(3, report.result.overallScore)
            statement.setString(4, report.result.summary)
            statement.setObject(5, jsonb(json.encodeToString(report.result.recommendations)))
            statement.setTimestamp(6, java.sql.Timestamp.from(report.createdAt))
            statement.executeUpdate()
        }
    }

    /** Вставляет все fixed criteria report. */
    private fun insertCriteria(connection: Connection, report: TrainingReport) {
        connection.prepareStatement(
            "INSERT INTO training_report_items (id, report_id, criterion_name, score, comment, evidence) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            report.result.criteria.forEach { criterion ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, report.id)
                statement.setString(3, criterion.name)
                statement.setInt(4, criterion.score)
                statement.setString(5, criterion.comment)
                statement.setString(6, criterion.evidence)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** Читает criteria report из нормализованной таблицы. */
    private fun findCriteria(connection: Connection, reportId: UUID): List<EvaluationCriterion> = connection.prepareStatement(
        "SELECT criterion_name, score, comment, evidence FROM training_report_items WHERE report_id = ? ORDER BY id ASC",
    ).use { statement ->
        statement.setObject(1, reportId)
        statement.executeQuery().use { results ->
            buildList {
                while (results.next()) {
                    add(EvaluationCriterion(results.getString("criterion_name"), results.getInt("score"), results.getString("comment"), results.getString("evidence")))
                }
            }
        }
    }

    /** Создаёт JDBC-значение PostgreSQL типа JSONB. */
    private fun jsonb(value: String): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}
