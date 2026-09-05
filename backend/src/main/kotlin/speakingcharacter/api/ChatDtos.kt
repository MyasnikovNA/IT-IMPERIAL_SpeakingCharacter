/** Содержит компактные JSON-модели запросов и ответов Chat API. */
package speakingcharacter.api

import kotlinx.serialization.Serializable

/** Входящая реплика из браузера: отсутствие сессии начинает новый диалог. */
@Serializable
data class ChatRequest(val sessionId: String? = null, val message: String)

/** Завершённый ход LLM, возвращаемый frontend для озвучивания в D-ID. */
@Serializable
data class ChatResponse(val sessionId: String, val assistantMessage: String)

/** Одно сохранённое сообщение для диагностического endpoint истории. */
@Serializable
data class HistoryMessageDto(val role: String, val content: String, val createdAt: String)

/** Хронологическая отладочная история существующей сессии. */
@Serializable
data class HistoryResponse(val sessionId: String, val messages: List<HistoryMessageDto>)

/** Короткое публичное сообщение об ошибке без секретов конфигурации. */
@Serializable
data class ErrorResponse(val error: String)

/** Тело успешного health-check ответа. */
@Serializable
data class HealthResponse(val status: String = "ok")
