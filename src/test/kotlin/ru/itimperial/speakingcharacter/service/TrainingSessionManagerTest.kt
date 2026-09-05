package ru.itimperial.speakingcharacter.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.llm.LlmClient
import ru.itimperial.speakingcharacter.model.ServerEvent
import ru.itimperial.speakingcharacter.model.TrainingMessage
import ru.itimperial.speakingcharacter.model.TrainingSession
import ru.itimperial.speakingcharacter.repository.TrainingRepository
import ru.itimperial.speakingcharacter.scenario.ScenarioCatalog
import ru.itimperial.speakingcharacter.scenario.ScenarioPromptProvider
import ru.itimperial.speakingcharacter.scenario.ScenarioResolver
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import ru.itimperial.speakingcharacter.scenario.ScenarioSelection

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSessionManagerTest {
    /** Закрепляет выбранный сценарий в сессии и передаёт его только в system prompt. */
    @Test
    fun `session keeps scenario snapshot for subsequent generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val config = testConfig()
        var capturedSystemPrompt = ""
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow {
                capturedSystemPrompt = systemPrompt
                emit("answer")
            }

            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean) = "{}"
        }
        val manager = manager(repo, llm, config, dispatcher)
        val session = manager.createSession(ScenarioSelection(presetId = "sales-discovery"))

        manager.submitUserMessage(session.id, 1, "Начнём")
        advanceUntilIdle()

        val saved = requireNotNull(repo.get(session.id))
        assertEquals("sales-discovery", saved.scenarioSnapshot?.definition?.id)
        assertContains(capturedSystemPrompt, "Выявление потребности B2B-клиента")
        assertContains(capturedSystemPrompt, "Конфигурация тренировки от методиста")
    }

    @Test
    fun `new generation cancels old response and keeps latest`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val config = testConfig()
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow {
                val last = history.last().text
                if (last == "first") {
                    emit("old-")
                    delay(500)
                    emit("should-not-arrive")
                } else {
                    emit("new-answer")
                }
            }

            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean): String =
                "{\"summary\":\"ok\",\"criteria\":[],\"mistakes\":[],\"recommendations\":[]}"
        }
        val manager = manager(repo, llm, config, dispatcher)
        val session = manager.createSession()
        val events = mutableListOf<ServerEvent>()
        val collector = launch(dispatcher) {
            manager.events(session.id).toList(events)
        }

        manager.submitUserMessage(session.id, 1, "first")
        testScheduler.advanceTimeBy(1)
        manager.submitUserMessage(session.id, 2, "second")
        advanceUntilIdle()

        val saved = repo.get(session.id)!!
        assertEquals(2, saved.latestGenerationId)
        assertTrue(saved.messages.any { it.text == "new-answer" })
        assertTrue(saved.messages.none { it.text.contains("should-not-arrive") })
        assertTrue(events.any { it is ServerEvent.GenerationCancelled && it.generationId == 1L })
        assertTrue(events.any { it is ServerEvent.AssistantCompleted && it.generationId == 2L })
        collector.cancel()
    }

    /** Создаёт менеджер с реальными сценарными зависимостями и тестовыми adapters. */
    private fun manager(repo: InMemoryRepository, llm: LlmClient, config: AppConfig, dispatcher: TestDispatcher): TrainingSessionManager {
        val reportService = ReportService(llm, config, Json { ignoreUnknownKeys = true })
        return TrainingSessionManager(
            repository = repo,
            llmClient = llm,
            reportService = reportService,
            appConfig = config,
            scenarioResolver = ScenarioResolver(ScenarioCatalog()),
            scenarioPromptProvider = ScenarioPromptProvider(),
            coroutineContext = dispatcher,
        )
    }

    private fun testConfig() = AppConfig(
        host = "localhost",
        port = 8080,
        geminiApiKey = "test",
        geminiModel = "gemini-2.5-flash-lite",
        didAgentId = null,
        didClientKey = null,
        dataDir = Path.of("build/test-data"),
        allowedOrigins = emptySet(),
        trainingSystemPrompt = "test",
        trainingCriteria = null,
    )

    private class InMemoryRepository : TrainingRepository {
        private val data = linkedMapOf<String, TrainingSession>()
        override suspend fun create(session: TrainingSession): TrainingSession = session.also { data[it.id] = it }
        override suspend fun get(sessionId: String): TrainingSession? = data[sessionId]
        override suspend fun save(session: TrainingSession): TrainingSession = session.also { data[it.id] = it }
        override suspend fun update(
            sessionId: String,
            transform: (TrainingSession) -> TrainingSession,
        ): TrainingSession? {
            val current = data[sessionId] ?: return null
            return transform(current).also { data[sessionId] = it }
        }
    }
}
