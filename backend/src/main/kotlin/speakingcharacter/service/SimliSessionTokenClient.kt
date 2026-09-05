/** Запрашивает короткоживущий Simli token, не раскрывая ключ браузеру. */
package speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import speakingcharacter.config.AppConfig

/** Безопасная ошибка получения Simli session token. */
class SimliException(message: String) : RuntimeException(message)

/** Получает token одной Simli avatar-сессии с ограничениями времени и простоя. */
class SimliSessionTokenClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) {
    private val logger = LoggerFactory.getLogger(SimliSessionTokenClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Вызывает Simli Compose API и возвращает только session token для браузера. */
    suspend fun createSessionToken(): String {
        val apiKey = requireNotNull(config.simliApiKey) { "SIMLI_API_KEY is not configured" }
        val faceId = requireNotNull(config.simliFaceId) { "SIMLI_FACE_ID is not configured" }
        val startedAt = System.nanoTime()
        val response = try {
            httpClient.post {
                url("https://api.simli.ai/compose/token")
                header("x-simli-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("faceId", JsonPrimitive(faceId))
                    put("handleSilence", JsonPrimitive(true))
                    put("maxSessionLength", JsonPrimitive(config.simliMaxSessionSeconds))
                    put("maxIdleTime", JsonPrimitive(config.simliMaxIdleSeconds))
                })
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
