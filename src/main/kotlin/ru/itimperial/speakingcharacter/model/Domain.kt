package ru.itimperial.speakingcharacter.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.itimperial.speakingcharacter.scenario.ScenarioSnapshot

@Serializable
enum class SessionStatus {
    ACTIVE,
    FINISHED,
}

@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("model") MODEL,
}

@Serializable
data class TrainingMessage(
    val role: MessageRole,
    val text: String,
    val generationId: Long,
    val createdAt: String,
    val interrupted: Boolean = false,
)

@Serializable
data class CriterionScore(
    val name: String,
    val score: Int,
    val comment: String,
    val evidence: String,
)

@Serializable
data class TrainingMetric(
    val name: String,
    val generationId: Long? = null,
    val valueMs: Long,
    val recordedAt: String,
)

@Serializable
data class TrainingReport(
    val generatedAt: String,
    val summary: String,
    val overallScore: Int,
    val criteria: List<CriterionScore> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val evaluationStatus: String,
)

@Serializable
data class TrainingSession(
    val id: String,
    val createdAt: String,
    val updatedAt: String,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val scenarioSnapshot: ScenarioSnapshot? = null,
    /** Временно сохраняет legacy criteria до переноса строгого отчёта. */
    val criteria: String? = null,
    val latestGenerationId: Long = -1,
    val messages: List<TrainingMessage> = emptyList(),
    val metrics: List<TrainingMetric> = emptyList(),
    val report: TrainingReport? = null,
)
