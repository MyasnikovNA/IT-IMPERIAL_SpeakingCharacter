package ru.itimperial.speakingcharacter.repository

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import ru.itimperial.speakingcharacter.model.MessageRole
import ru.itimperial.speakingcharacter.model.TrainingMessage
import ru.itimperial.speakingcharacter.model.TrainingSession

/** Проверяет file persistence session aggregate между независимыми экземплярами repository. */
class FileTrainingRepositoryTest {
    /** Сохраняет update атомарно и читает его новым repository после имитации restart. */
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `persists aggregate update across repository restart`() = runTest {
        val directory = Files.createTempDirectory("speaking-character-repository-")
        val json = Json { encodeDefaults = true; explicitNulls = false }
        try {
            val initial = TrainingSession(
                id = "session-1",
                createdAt = "2026-09-07T00:00:00Z",
                updatedAt = "2026-09-07T00:00:00Z",
            )
            val firstRepository = FileTrainingRepository(directory, json)
            firstRepository.create(initial)
            firstRepository.update(initial.id) { session ->
                session.copy(
                    latestGenerationId = 1,
                    messages = listOf(
                        TrainingMessage(
                            role = MessageRole.USER,
                            text = "Ответ сотрудника",
                            generationId = 1,
                            createdAt = "2026-09-07T00:00:01Z",
                        ),
                    ),
                )
            }

            val restored = FileTrainingRepository(directory, json).get(initial.id)

            assertNotNull(restored)
            assertEquals(1, restored.latestGenerationId)
            assertEquals("Ответ сотрудника", restored.messages.single().text)
        } finally {
            directory.deleteRecursively()
        }
    }
}
