package ru.itimperial.speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import ru.itimperial.speakingcharacter.config.AppConfig

class SimliException(message: String) : RuntimeException(message)

class SimliSessionTokenClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) {
    private val logger = LoggerFactory.getLogger(SimliSessionTokenClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createSessionToken(): String {
        val apiKey = requireNotNull(config.simliApiKey) { "SIMLI_API_KEY is not configured" }
        val faceId = requireNotNull(config.simliFaceId) { "SIMLI_FACE_ID is not configured" }
        val startedAt = System.nanoTime()
        val payload = buildJsonObject {
            put("faceId", JsonPrimitive(faceId))
            put("handleSilence", JsonPrimitive(true))
            put("maxSessionLength", JsonPrimitive(config.simliMaxSessionSeconds))
            put("maxIdleTime", JsonPrimitive(config.simliMaxIdleSeconds))
        }
        val response = try {
            httpClient.post {
                url("https://api.simli.ai/compose/token")
                header("x-simli-api-key", apiKey)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(payload.toString())
            }
        } catch (_: Exception) {
            throw SimliException("Simli session token request failed")
        }
        logger.info("simli_session_token_response durationMs={} status={}", (System.nanoTime() - startedAt) / 1_000_000, response.status.value)
        if (response.status.value !in 200..299) throw SimliException("Simli session token request failed")
        return try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject["session_token"]
                ?.let { it as? JsonPrimitive }?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: throw SimliException("Simli returned no session token")
        } catch (exception: SimliException) {
            throw exception
        } catch (_: Exception) {
            throw SimliException("Simli returned an invalid session token")
        }
    }
}
