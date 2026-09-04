/** Вызывает официальный REST API Gemini с системным prompt и сохранённой историей. */
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import speakingcharacter.config.AppConfig
import speakingcharacter.db.ChatMessage
import speakingcharacter.db.ChatRole

/** Безопасная ошибка приложения: Gemini недоступен или вернул некорректный ответ. */
class GeminiException(message: String) : RuntimeException(message)

/** Минимальный HTTP-клиент Gemini без лишнего AI-фреймворка для MVP. */
class GeminiClient(private val httpClient: HttpClient, private val config: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Генерирует один ответ ассистента по внешнему system prompt и хронологической истории.
     *
     * @param systemPrompt инструкции роли, загруженные из prompt-ресурса
     * @param messages сохранённые реплики пользователя и ассистента с текущей репликой ровно один раз
     */
    suspend fun generate(systemPrompt: String, messages: List<ChatMessage>): String {
        val response = try {
            httpClient.post {
                url("https://generativelanguage.googleapis.com/v1beta/models/${config.geminiModel}:generateContent")
                header("x-goog-api-key", config.geminiApiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody(systemPrompt, messages))
            }
        } catch (_: Exception) {
            throw GeminiException("Gemini request failed due to a network or client error")
        }

        if (response.status.value !in 200..299) {
            throw GeminiException("Gemini returned HTTP ${response.status.value}. Check GEMINI_MODEL and API configuration.")
        }

        return extractText(response.bodyAsText())
    }

    /** Сериализует запрос GenerateContent в REST-схему Gemini. */
    private fun requestBody(systemPrompt: String, messages: List<ChatMessage>): JsonObject = buildJsonObject {
        put("systemInstruction", contentPart(systemPrompt))
        put("contents", buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive(if (message.role == ChatRole.USER) "user" else "model"))
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(message.content)) }) })
                    },
                )
            }
        })
        put("generationConfig", buildJsonObject { put("maxOutputTokens", JsonPrimitive(200)) })
    }

    /** Оборачивает текст инструкции в представление parts, требуемое Gemini. */
    private fun contentPart(text: String): JsonObject = buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(text)) }) })
    }

    /** Извлекает видимый текст всех частей первого кандидата или сообщает безопасную ошибку. */
    private fun extractText(rawResponse: String): String = try {
        val root = json.parseToJsonElement(rawResponse).jsonObject
        val candidates = root["candidates"]?.jsonArray ?: throw GeminiException("Gemini returned no candidates")
        val candidate = candidates.firstOrNull() ?: throw GeminiException("Gemini returned no candidates")
        val parts = candidate.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?: throw GeminiException("Gemini returned no text content")
        parts.mapNotNull { part ->
            val partObject = part as? JsonObject ?: return@mapNotNull null
            if ((partObject["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            (partObject["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("").trim().takeIf { it.isNotEmpty() }
            ?: throw GeminiException("Gemini returned an empty response")
    } catch (exception: GeminiException) {
        throw exception
    } catch (_: Exception) {
        throw GeminiException("Gemini returned an invalid response")
    }
}
