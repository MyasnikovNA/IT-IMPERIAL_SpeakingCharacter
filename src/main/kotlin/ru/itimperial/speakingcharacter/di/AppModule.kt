package ru.itimperial.speakingcharacter.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.config.StorageBackend
import ru.itimperial.speakingcharacter.llm.GeminiLlmClient
import ru.itimperial.speakingcharacter.llm.LlmClient
import ru.itimperial.speakingcharacter.repository.DatabaseFactory
import ru.itimperial.speakingcharacter.repository.FileTrainingRepository
import ru.itimperial.speakingcharacter.repository.PostgresTrainingRepository
import ru.itimperial.speakingcharacter.repository.TrainingRepository
import ru.itimperial.speakingcharacter.service.ElevenLabsStreamingTtsClient
import ru.itimperial.speakingcharacter.service.ReportService
import ru.itimperial.speakingcharacter.service.SimliSessionTokenClient
import ru.itimperial.speakingcharacter.service.StreamingTtsClient
import ru.itimperial.speakingcharacter.service.TrainingSessionManager
import ru.itimperial.speakingcharacter.scenario.ScenarioCatalog
import ru.itimperial.speakingcharacter.scenario.ScenarioPromptProvider
import ru.itimperial.speakingcharacter.scenario.ScenarioResolver
import javax.sql.DataSource

fun appModule(config: AppConfig) = module {
    single { config }
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
    single {
        HttpClient(CIO) {
            expectSuccess = false
            install(WebSockets)
        }
    }

    if (config.storageBackend == StorageBackend.POSTGRES) {
        single<DataSource> { DatabaseFactory.create(get()) }
        single<TrainingRepository> { PostgresTrainingRepository(get(), get()) }
    } else {
        single<TrainingRepository> { FileTrainingRepository(get<AppConfig>().dataDir, get()) }
    }

    single<LlmClient> {
        GeminiLlmClient(
            httpClient = get(),
            json = get(),
            apiKey = get<AppConfig>().geminiApiKey,
            model = get<AppConfig>().geminiModel,
            fallbackModels = get<AppConfig>().geminiFallbackModels,
        )
    }
    single<StreamingTtsClient> { ElevenLabsStreamingTtsClient(get(), get()) }
    single { SimliSessionTokenClient(get(), get()) }
    single(createdAtStart = true) { ScenarioCatalog() }
    single { ScenarioResolver(get()) }
    single { ScenarioPromptProvider() }
    single { ReportService(get(), get()) }
    single {
        TrainingSessionManager(
            repository = get(),
            llmClient = get(),
            reportService = get(),
            appConfig = get(),
            scenarioResolver = get(),
            scenarioPromptProvider = get(),
            coroutineContext = Dispatchers.IO,
        )
    }
}
