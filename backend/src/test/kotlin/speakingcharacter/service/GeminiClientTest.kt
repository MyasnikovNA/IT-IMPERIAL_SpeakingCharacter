/** Проверяет HTTP-интеграцию Gemini без сети через Ktor MockEngine. */
package speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import speakingcharacter.config.AppConfig
import speakingcharacter.db.ChatMessage
import speakingcharacter.db.ChatRole
import java.time.Instant

/** Тестирует формирование запроса и обработку ответов GeminiClient. */
class GeminiClientTest {
    /** Передаёт system prompt и роли сообщений Gemini, а затем возвращает текст кандидата. */
    @Test
    fun `generate serializes conversation and returns candidate text`() = runTest {
        var requestUrl = ""
        var requestBody = ""
        val client = mockClient("""{"candidates":[{"content":{"parts":[{"text":"  Готовый ответ  "}]}}]}""") { request ->
            requestUrl = request.url.toString()
            requestBody = (request.body as TextContent).text
        }
        val gemini = GeminiClient(client, testConfig())

        val result = gemini.generate(
            systemPrompt = "Ты корпоративный персонаж.",
            messages = listOf(message(ChatRole.USER, "Здравствуйте"), message(ChatRole.ASSISTANT, "Добрый день")),
        )

        assertEquals("Готовый ответ", result)
        assertContains(requestUrl, "models/test-model:generateContent")
        assertContains(requestUrl, "key=test-api-key")
        assertContains(requestBody, "Ты корпоративный персонаж.")
        assertContains(requestBody, "\"role\":\"user\"")
        assertContains(requestBody, "\"role\":\"model\"")
        assertContains(requestBody, "Здравствуйте")
        assertContains(requestBody, "Добрый день")
        client.close()
    }

    /** Преобразует неуспешный HTTP-статус Gemini в безопасную ошибку конфигурации. */
    @Test
    fun `generate reports non-success Gemini status`() = runTest {
        val client = mockClient("""{"error":{"message":"model unavailable"}}""", HttpStatusCode.NotFound)
        val gemini = GeminiClient(client, testConfig())

        val error = geminiFailure { gemini.generate("Инструкция", listOf(message(ChatRole.USER, "Текст"))) }

        assertContains(error.message.orEmpty(), "HTTP 404")
        assertContains(error.message.orEmpty(), "GEMINI_MODEL")
        client.close()
    }

    /** Отклоняет успешный HTTP-ответ без текста кандидата. */
    @Test
    fun `generate rejects response without candidate text`() = runTest {
        val client = mockClient("""{"candidates":[]}""")
        val gemini = GeminiClient(client, testConfig())

        val error = geminiFailure { gemini.generate("Инструкция", listOf(message(ChatRole.USER, "Текст"))) }

        assertContains(error.message.orEmpty(), "no candidates")
        client.close()
    }

    /** Создаёт тестовый HTTP-клиент, не выполняющий реальных сетевых запросов. */
    private fun mockClient(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine { request ->
        onRequest(request)
        respond(responseBody, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /** Создаёт нейтральную конфигурацию, достаточную для изолированного Gemini-теста. */
    private fun testConfig(): AppConfig = AppConfig(
        geminiApiKey = "test-api-key",
        geminiModel = "test-model",
        databaseUrl = "jdbc:postgresql://unused",
        databaseUser = "unused",
        databasePassword = "unused",
        maxContextMessages = 20,
        frontendHost = "localhost:8000",
    )

    /** Создаёт одно тестовое сообщение с постоянной меткой времени. */
    private fun message(role: ChatRole, content: String): ChatMessage =
        ChatMessage(role, content, Instant.parse("2026-01-01T00:00:00Z"))

    /** Выполняет suspend-действие и возвращает ожидаемую ошибку Gemini. */
    private suspend fun geminiFailure(action: suspend () -> Unit): GeminiException = try {
        action()
        throw AssertionError("Ожидалась ошибка GeminiException")
    } catch (error: GeminiException) {
        error
    }
}
