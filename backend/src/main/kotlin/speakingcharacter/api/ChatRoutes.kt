/** Определяет HTTP endpoints чата и диагностической истории. */
package speakingcharacter.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import speakingcharacter.service.ConversationResult
import speakingcharacter.service.ConversationService
import speakingcharacter.service.GeminiException
import speakingcharacter.service.SessionFinishedException
import speakingcharacter.service.SessionNotFoundException
import java.util.UUID

/** Регистрирует существующий API чата без изменения POST /api/chat contract. */
fun Application.registerChatRoutes(conversationService: ConversationService) {
    routing {
        get("/health") { call.respond(HealthResponse()) }
        route("/api/chat") {
            post {
                val request = call.receive<ChatRequest>()
                val message = request.message.trim()
                if (message.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("message must not be empty"))
                    return@post
                }
                val sessionId = request.sessionId?.let { call.parseSessionId(it) }
                if (request.sessionId != null && sessionId == null) return@post
                val result = call.executeReply(conversationService, sessionId, message) ?: return@post
                call.respond(ChatResponse(result.sessionId.toString(), result.assistantMessage))
            }
            get("/{sessionId}/history") {
                val sessionId = call.parseSessionId(call.parameters["sessionId"] ?: "") ?: return@get
                try {
                    val messages = conversationService.history(sessionId).map { message ->
                        HistoryMessageDto(message.role.name, message.content, message.createdAt.toString())
                    }
                    call.respond(HistoryResponse(sessionId.toString(), messages))
                } catch (_: SessionNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
                }
            }
        }
    }
}

/** Разбирает UUID или отправляет единообразный ответ о некорректном запросе. */
private suspend fun ApplicationCall.parseSessionId(rawSessionId: String): UUID? = try {
    UUID.fromString(rawSessionId)
} catch (_: IllegalArgumentException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId must be a UUID"))
    null
}

/** Вызывает chat service и сопоставляет ожидаемые domain errors с HTTP-ответами. */
private suspend fun ApplicationCall.executeReply(
    service: ConversationService,
    sessionId: UUID?,
    message: String,
): ConversationResult? = try {
    service.reply(sessionId, message)
} catch (_: SessionNotFoundException) {
    respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
    null
} catch (_: SessionFinishedException) {
    respond(HttpStatusCode.Conflict, ErrorResponse("training session is already finished"))
    null
} catch (exception: GeminiException) {
    respond(HttpStatusCode.BadGateway, ErrorResponse(exception.message ?: "Gemini request failed"))
    null
}
