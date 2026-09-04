/** Определяет HTTP endpoints чата, истории, завершения тренировки и report. */
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
import speakingcharacter.service.EvaluationException
import speakingcharacter.service.EvaluationService
import speakingcharacter.service.GeminiException
import speakingcharacter.service.SessionFinishedException
import speakingcharacter.service.SessionNotFoundException
import java.util.UUID
import org.slf4j.LoggerFactory

private val chatRoutesLogger = LoggerFactory.getLogger("ChatRoutes")

/** Регистрирует API чата без изменения существующего POST /api/chat contract. */
fun Application.registerChatRoutes(conversationService: ConversationService, evaluationService: EvaluationService) {
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
            post("/{sessionId}/finish") {
                val sessionId = call.parseSessionId(call.parameters["sessionId"] ?: "") ?: return@post
                val report = call.executeEvaluation { evaluationService.finishSession(sessionId) } ?: return@post
                call.respond(report.toDto())
            }
            get("/{sessionId}/report") {
                val sessionId = call.parseSessionId(call.parameters["sessionId"] ?: "") ?: return@get
                val report = call.executeEvaluation { evaluationService.getReport(sessionId) } ?: return@get
                call.respond(report.toDto())
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
): ConversationResult? {
    /** Измеряет полный server-side latency POST /api/chat без записи текста сообщения. */
    val startedAt = System.nanoTime()
    return try {
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
    } finally {
        chatRoutesLogger.info(
            "Chat HTTP request completed durationMs={} sessionIdPresent={}",
            (System.nanoTime() - startedAt) / 1_000_000,
            sessionId != null,
        )
    }
}

/** Выполняет finish или report flow и не раскрывает stack trace API-клиенту. */
private suspend fun ApplicationCall.executeEvaluation(action: suspend () -> speakingcharacter.model.TrainingReport): speakingcharacter.model.TrainingReport? = try {
    action()
} catch (_: SessionNotFoundException) {
    respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
    null
} catch (_: NoSuchElementException) {
    respond(HttpStatusCode.NotFound, ErrorResponse("training report not found"))
    null
} catch (exception: EvaluationException) {
    respond(HttpStatusCode.BadGateway, ErrorResponse(exception.message ?: "evaluation failed"))
    null
} catch (_: IllegalStateException) {
    respond(HttpStatusCode.BadRequest, ErrorResponse("training session has no messages"))
    null
}
