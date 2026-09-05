package ru.itimperial.speakingcharacter.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.config.AvatarProvider
import ru.itimperial.speakingcharacter.model.ClientEvent
import ru.itimperial.speakingcharacter.model.CreateSessionRequest
import ru.itimperial.speakingcharacter.model.CreateSessionResponse
import ru.itimperial.speakingcharacter.model.FrontendConfig
import ru.itimperial.speakingcharacter.model.InterruptRequest
import ru.itimperial.speakingcharacter.model.ServerEvent
import ru.itimperial.speakingcharacter.service.TrainingSessionManager

fun Application.configureRoutes() {
    val manager by inject<TrainingSessionManager>()
    val json by inject<Json>()
    val config by inject<AppConfig>()

    routing {
        get("/health") {
            call.respond(
                mapOf(
                    "status" to "ok",
                    "storage" to config.storageBackend.name.lowercase(),
                    "avatarProvider" to config.avatarProvider.name.lowercase(),
                ),
            )
        }

        route("/api") {
            get("/config") {
                if (config.avatarProvider == AvatarProvider.DID &&
                    (config.didAgentId == null || config.didClientKey == null)
                ) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "D-ID frontend credentials are not configured"),
                    )
                    return@get
                }
                call.respond(
                    FrontendConfig(
                        avatarProvider = config.avatarProvider.name.lowercase(),
                        chatApiUrl = config.publicApiUrl,
                        agentId = config.didAgentId.takeIf { config.avatarProvider == AvatarProvider.DID },
                        clientKey = config.didClientKey.takeIf { config.avatarProvider == AvatarProvider.DID },
                    ),
                )
            }

            post("/sessions") {
                val rawBody = call.receiveText()
                val request = if (rawBody.isBlank()) {
                    CreateSessionRequest()
                } else {
                    json.decodeFromString<CreateSessionRequest>(rawBody)
                }
                val session = manager.createSession(request.scenario, request.criteria)
                val scheme = if (call.request.local.scheme == "https") "wss" else "ws"
                val host = call.request.local.serverHost
                val port = call.request.local.serverPort
                val hostPort = if ((scheme == "ws" && port == 80) || (scheme == "wss" && port == 443)) {
                    host
                } else {
                    "$host:$port"
                }
                call.respond(
                    HttpStatusCode.Created,
                    CreateSessionResponse(
                        sessionId = session.id,
                        websocketUrl = "$scheme://$hostPort/ws/training/${session.id}",
                    ),
                )
            }

            get("/sessions/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val session = manager.getSession(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(session)
            }

            post("/sessions/{id}/interrupt") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<InterruptRequest>()
                manager.interrupt(id, request.generationId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/sessions/{id}/finish") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(manager.finish(id))
            }

            get("/sessions/{id}/report") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val session = manager.getSession(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val report = session.report
                    ?: return@get call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Report is not ready. Finish the session first."),
                    )
                call.respond(report)
            }
        }

        webSocket("/ws/training/{sessionId}") {
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && config.allowedOrigins.isNotEmpty() && origin !in config.allowedOrigins) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin is not allowed"))
                return@webSocket
            }

            val sessionId = call.parameters["sessionId"] ?: return@webSocket
            val session = manager.getSession(sessionId)
            if (session == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Training session not found"))
                return@webSocket
            }

            sendSerialized(
                json,
                ServerEvent.Connected(
                    sessionId = sessionId,
                    latestGenerationId = session.latestGenerationId,
                ),
            )

            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.events(sessionId).collect { event ->
                    sendSerialized(json, event)
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val raw = frame.readText()
                    val event = try {
                        json.decodeFromString<ClientEvent>(raw)
                    } catch (e: Exception) {
                        sendSerialized(
                            json,
                            ServerEvent.Error(
                                code = "BAD_EVENT",
                                message = e.message ?: "Invalid WebSocket event",
                            ),
                        )
                        continue
                    }

                    when (event) {
                        is ClientEvent.UserMessage -> manager.submitUserMessage(
                            sessionId = sessionId,
                            generationId = event.generationId,
                            text = event.text,
                        )

                        is ClientEvent.Interrupt -> manager.interrupt(
                            sessionId = sessionId,
                            generationId = event.generationId,
                        )

                        is ClientEvent.Metric -> manager.recordMetric(
                            sessionId = sessionId,
                            name = event.name,
                            generationId = event.generationId,
                            valueMs = event.valueMs,
                        )

                        is ClientEvent.Finish -> manager.finish(sessionId)
                    }
                }
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    configureChatCompatibilityRoutes()
    configureSimliRoutes()
    configureMonitoringRoutes()
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendSerialized(
    json: Json,
    event: ServerEvent,
) {
    send(Frame.Text(json.encodeToString<ServerEvent>(event)))
}
