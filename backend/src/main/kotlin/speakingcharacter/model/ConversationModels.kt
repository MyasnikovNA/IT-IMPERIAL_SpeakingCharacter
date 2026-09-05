/** Описывает модели тренировочной сессии, сообщений и подготовленного LLM-контекста. */
package speakingcharacter.model

import java.time.Instant
import java.util.UUID
import speakingcharacter.scenario.ScenarioSnapshot

/** Роли сообщений, сохраняемые в истории диалога. */
enum class ChatRole { USER, ASSISTANT }

/** Состояния жизненного цикла тренировочной сессии. */
enum class SessionStatus { ACTIVE, FINISHED, FAILED }

/** Одно сохранённое сообщение тренировочного диалога. */
data class ChatMessage(val role: ChatRole, val content: String, val createdAt: Instant)

/** Состояние тренировочной сессии, используемое сервисами orchestration. */
data class TrainingSession(
    val id: UUID,
    val status: SessionStatus,
    val scenarioId: String?,
    val finishedAt: Instant?,
    val scenarioSnapshot: ScenarioSnapshot? = null,
)

/** Сообщение с ролью в формате Gemini, подготовленное до вызова inference. */
data class LlmMessage(val role: String, val content: String)
