/** Вызывает официальный REST API Gemini с системным prompt и сохранённой историей. */
package speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import io.ktor.utils.io.readUTF8Line
import speakingcharacter.config.AppConfig
import speakingcharacter.model.LlmMessage

/** Безопасная ошибка приложения: Gemini недоступен или вернул некорректный ответ. */
class GeminiException(message: String) : RuntimeException(message)

/** Минимальный HTTP-клиент Gemini без лишнего AI-фреймворка для MVP. */
class GeminiClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
    private val streamingHttpClient: HttpClient = httpClient,
) : LlmClient {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Генерирует один ответ ассистента по внешнему system prompt и хронологической истории.
     *
     * @param systemPrompt инструкции роли, загруженные из prompt-ресурса
     * @param messages подготовленные реплики Gemini с текущей user-репликой ровно один раз
     */
    override suspend fun generate(systemPrompt: String, messages: List<LlmMessage>): String =
        requestInference(systemPrompt, messages, 200, false)

    /** Генерирует JSON-отчёт с увеличенным лимитом, не предназначенный для озвучивания. */
    override suspend fun generateStructuredJson(systemPrompt: String, messages: List<LlmMessage>): String =
        requestInference(systemPrompt, messages, 800, true)

    /**
     * Получает Gemini SSE и передаёт только новые видимые текстовые фрагменты.
     *
     * Резервная модель используется лишь пока первая модель ещё не отдала ни одной дельты:
     * иначе переключение привело бы к повторению уже озвученного текста.
     */
    override fun generateStream(systemPrompt: String, messages: List<LlmMessage>): Flow<String> = flow {
        val models = (listOf(config.geminiModel) + config.geminiFallbackModels).distinct()
        var lastFailure: GeminiException? = null

        for ((index, model) in models.withIndex()) {
            var emittedDelta = false
            try {
                requestStream(model, systemPrompt, messages).collect { delta ->
                    emittedDelta = true
                    emit(delta)
                }
                return@flow
            } catch (exception: GeminiException) {
                lastFailure = exception
                if (emittedDelta || index == models.lastIndex) throw exception
            } catch (_: Exception) {
                val failure = GeminiException("Gemini stream failed due to a network or client error")
                lastFailure = failure
                if (emittedDelta || index == models.lastIndex) throw failure
            }
        }
        throw lastFailure ?: GeminiException("Gemini stream failed")
    }

    /** Выполняет один Gemini запрос с заданным форматом и лимитом ответа. */
    private suspend fun requestInference(
        systemPrompt: String,
        messages: List<LlmMessage>,
        maxOutputTokens: Int,
        structuredJson: Boolean,
    ): String {
        val models = (listOf(config.geminiModel) + config.geminiFallbackModels).distinct()
        var lastFailure: GeminiException? = null

        for ((index, model) in models.withIndex()) {
            val response = try {
                httpClient.post {
                    url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    header("x-goog-api-key", config.geminiApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody(systemPrompt, messages, maxOutputTokens, structuredJson))
                }
            } catch (_: Exception) {
                lastFailure = GeminiException("Gemini request failed due to a network or client error")
                if (index == models.lastIndex) throw lastFailure!!
                continue
            }

            if (response.status.value in 200..299) {
                return extractText(response.bodyAsText())
            }

            lastFailure = GeminiException("Gemini returned HTTP ${response.status.value}. Check GEMINI_MODEL and API configuration.")
            val retryable = response.status.value == 404 || response.status.value == 408 ||
                response.status.value == 429 || response.status.value >= 500
            if (!retryable || index == models.lastIndex) throw lastFailure!!
        }

        throw lastFailure ?: GeminiException("Gemini request failed")
    }

    /** Сериализует GenerateContent с форматом обычного текста либо structured JSON. */
    private fun requestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        maxOutputTokens: Int,
        structuredJson: Boolean,
    ): JsonObject = buildJsonObject {
        put("systemInstruction", contentPart(systemPrompt))
        put("contents", buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive(message.role))
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(message.content)) }) })
                    },
                )
            }
        })
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", JsonPrimitive(maxOutputTokens))
            if (structuredJson) put("responseMimeType", JsonPrimitive("application/json"))
        })
    }

    /** Оборачивает текст инструкции в представление parts, требуемое Gemini. */
    private fun contentPart(text: String): JsonObject = buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(text)) }) })
    }

    /** Выполняет один SSE-запрос к заданной модели и читает data-события до конца ответа. */
    private fun requestStream(model: String, systemPrompt: String, messages: List<LlmMessage>): Flow<String> = flow {
        val response = try {
            streamingHttpClient.post {
                url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
                header("x-goog-api-key", config.geminiApiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody(systemPrompt, messages, 200, false))
            }
        } catch (_: Exception) {
            throw GeminiException("Gemini stream failed due to a network or client error")
        }
        if (response.status.value !in 200..299) {
            throw GeminiException("Gemini returned HTTP ${response.status.value}. Check GEMINI_MODEL and API configuration.")
        }

        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val delta = extractSseTextDelta(line.removePrefix("data:").trim())
            if (delta.isNotEmpty()) emit(delta)
        }
    }

    /** Извлекает видимый текст одной Gemini SSE data-записи без внутреннего reasoning. */
    internal fun extractSseTextDelta(rawEvent: String): String = try {
        val root = json.parseToJsonElement(rawEvent).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return ""
        parts.mapNotNull { part ->
            val objectPart = part as? JsonObject ?: return@mapNotNull null
            if ((objectPart["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            (objectPart["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
    } catch (_: Exception) {
        throw GeminiException("Gemini returned an invalid SSE event")
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
