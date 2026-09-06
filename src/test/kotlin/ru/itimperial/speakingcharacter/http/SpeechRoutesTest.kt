package ru.itimperial.speakingcharacter.http

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.module

class SpeechRoutesTest {
    @Test
    fun `token endpoint is unavailable without server-side ElevenLabs key`() = testApplication {
        application {
            module(AppConfig.fromEnvironment(mapOf("GEMINI_API_KEY" to "test-key")))
        }

        val response = client.post("/api/stt/token")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.bodyAsText().contains("Speech recognition is not configured"))
    }
}
