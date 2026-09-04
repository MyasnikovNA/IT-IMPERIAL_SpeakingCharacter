/** Запускает Ktor, миграции БД, CORS и небольшой постоянный pipeline чата. */
package speakingcharacter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import speakingcharacter.api.ErrorResponse
import speakingcharacter.api.registerChatRoutes
import speakingcharacter.config.AppConfig
import speakingcharacter.db.DatabaseFactory
import speakingcharacter.db.JdbcChatRepository
import speakingcharacter.service.ConversationService
import speakingcharacter.service.ConversationContextBuilder
import speakingcharacter.service.GeminiClient
import speakingcharacter.service.PromptProvider

/** Настраивает и запускает все зависимости Kotlin backend чата. */
fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")
    val config = AppConfig.fromEnvironment()
    val dataSource = DatabaseFactory(config).connectAndMigrate()
    val chatRepository = JdbcChatRepository(dataSource)
    val promptProvider = PromptProvider()
    val contextBuilder = ConversationContextBuilder(config.maxContextMessages)
    val geminiClient = GeminiClient(HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }, config)
    val conversationService = ConversationService(chatRepository, contextBuilder, promptProvider, geminiClient)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(CORS) {
        allowHost(config.frontendHost, schemes = listOf("http"))
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader("Content-Type")
    }
    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request body"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled backend error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }
    registerChatRoutes(conversationService)
}
