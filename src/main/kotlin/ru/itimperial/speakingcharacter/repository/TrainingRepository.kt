package ru.itimperial.speakingcharacter.repository

import ru.itimperial.speakingcharacter.model.TrainingSession

interface TrainingRepository {
    suspend fun create(session: TrainingSession): TrainingSession
    suspend fun get(sessionId: String): TrainingSession?
    suspend fun save(session: TrainingSession): TrainingSession
    suspend fun update(sessionId: String, transform: (TrainingSession) -> TrainingSession): TrainingSession?
}
