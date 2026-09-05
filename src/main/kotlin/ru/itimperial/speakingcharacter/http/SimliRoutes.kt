package ru.itimperial.speakingcharacter.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import ru.itimperial.speakingcharacter.config.AppConfig
import ru.itimperial.speakingcharacter.config.AvatarProvider
import ru.itimperial.speakingcharacter.model.ChatStreamEvent
import ru.itimperial.speakingcharacter.model.ChatStreamRequest
import ru.itimperial.speakingcharacter.model.ErrorResponse
import ru.itimperial.speakingcharacter.model.ServerEvent
import ru.itimperial.speakingcharacter.model.SimliSessionResponse
import ru.itimperial.speakingcharacter.model.SubtitleCueEvent
import ru.itimperial.speakingcharacter.service.ElevenLabsException
import ru.itimperial.speakingcharacter.service.SimliException
import ru.itimperial.speakingcharacter.service.SimliSessionTokenClient
import ru.itimperial.speakingcharacter.service.StreamingTtsClient
import ru.itimperial.speakingcharacter.service.SubtitleCueBuilder
import ru.itimperial.speakingcharacter.service.TrainingSessionManager
import ru.itimperial.speakingcharacter.service.TurnLatencyTracker

private val streamingJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Simli compatibility route from simli-rnd, using the same generation manager as D-ID. */
fun Application.configureSimliRoutes() {
    val config by inject<AppConfig>()
    val manager by inject<TrainingSessionManager>()
    val ttsClient by inject<StreamingTtsClient>()
    val simliSessionTokenClient by inject<SimliSessionTokenClient>()

    routing {
        post("/api/avatar/simli/session") {
            if (config.avatarProvider != AvatarProvider.SIMLI) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Simli avatar provider is disabled"))
                return@post
            }
            try {
                call.respond(SimliSessionResponse(simliSessionTokenClient.createSessionToken(), config.simliTransport))
            } catch (exception: SimliException) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(exception.message ?: "Simli session request failed"))
            }
        }

        webSocket("/api/chat/stream") {
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && config.allowedOrigins.isNotEmpty() && origin !in config.allowedOrigins) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin is not allowed"))
                return@webSocket
            }
            if (config.avatarProvider != AvatarProvider.SIMLI) {
                send(Frame.Text(streamingJson.encodeToString(ChatStreamEvent.serializer(), ChatStreamEvent("error", error = "Simli avatar provider is disabled"))))
                return@webSocket
            }
            handleSimliTurn(manager, ttsClient)
        }
    }
}

private suspend fun WebSocketServerSession.handleSimliTurn(
    manager: TrainingSessionManager,
    ttsClient: StreamingTtsClient,
) {
    val start = receiveStart() ?: return
    val turnId = start.turnId ?: UUID.randomUUID().toString()
    val tracker = TurnLatencyTracker(turnId)
    tracker.mark("input_received")

    val session = if (start.sessionId == null) {
        manager.createSession(start.scenario?.toDomain())
    } else {
        if (start.scenario != null) {
            sendEvent(ChatStreamEvent("error", error = "scenario can only be selected for a new session", turnId = turnId))
            return
        }
        val existing = manager.getSession(start.sessionId)
        if (existing == null) {
            sendEvent(ChatStreamEvent("error", error = "chat session not found", turnId = turnId))
            return
        }
        existing
    }
    tracker.mark("session_ready")

    val outbound = Channel<Frame>(capacity = 8)
    val writer = launch(start = CoroutineStart.UNDISPATCHED) {
        for (frame in outbound) send(frame)
    }
    val textDeltas = Channel<String>(capacity = 2)
    val activeGeneration = AtomicLong(Long.MIN_VALUE)
    val terminal = CompletableDeferred<ServerEvent>()
    val sessionAnnounced = CompletableDeferred<Unit>()
    var firstPcm = false
    var frameId = 0L

    val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
        manager.events(session.id).collect { event ->
            val generationId = activeGeneration.get()
            if (generationId == Long.MIN_VALUE) return@collect
            sessionAnnounced.await()
            when (event) {
                is ServerEvent.AssistantDelta -> if (event.generationId == generationId) {
                    tracker.mark("gemini_first_delta")
                    outbound.sendEvent(
                        ChatStreamEvent(
                            type = "delta",
                            sessionId = session.id,
                            generationId = generationId,
                            delta = event.delta,
                            turnId = turnId,
                        ),
                    )
                    textDeltas.send(event.delta)
                }
                is ServerEvent.AssistantCompleted -> if (event.generationId == generationId) terminal.complete(event)
                is ServerEvent.GenerationCancelled -> if (event.generationId == generationId) terminal.complete(event)
                is ServerEvent.Error -> if (event.generationId == generationId) terminal.complete(event)
                is ServerEvent.StaleGeneration -> if (event.generationId == generationId) terminal.complete(event)
                else -> Unit
            }
        }
    }

    val ttsJob = launch {
        try {
            ttsClient.synthesize(textDeltas.receiveAsFlow()).collect { audio ->
                if (audio.pcm.isEmpty()) return@collect
                if (!firstPcm) {
                    firstPcm = true
                    tracker.mark("tts_first_audio")
                }
                val currentFrameId = frameId++
                val cues = SubtitleCueBuilder.build(audio.alignment).map { cue ->
                    SubtitleCueEvent(cue.text, cue.startMs, cue.endMs)
                }
                outbound.sendEvent(
                    ChatStreamEvent(
                        type = "audio_frame",
                        sessionId = session.id,
                        generationId = activeGeneration.get(),
                        turnId = turnId,
                        frameId = currentFrameId,
                        cues = cues,
                    ),
                )
                outbound.send(Frame.Binary(fin = true, data = audio.pcm))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ElevenLabsException) {
            terminal.complete(ServerEvent.Error("TTS_FAILED", e.message ?: "TTS stream failed", activeGeneration.get()))
        } catch (_: Exception) {
            terminal.complete(ServerEvent.Error("TTS_FAILED", "TTS stream failed", activeGeneration.get()))
        }
    }

    var commandJob: kotlinx.coroutines.Job? = null
    try {
        val generationId = try {
            if (start.generationId != null) {
                activeGeneration.set(start.generationId)
                manager.submitUserMessage(session.id, start.generationId, requireNotNull(start.message).trim())
                start.generationId
            } else {
                manager.submitNextUserMessage(session.id, requireNotNull(start.message).trim()) { activeGeneration.set(it) }
            }
        } catch (e: IllegalStateException) {
            outbound.sendEvent(ChatStreamEvent("error", sessionId = session.id, error = e.message ?: "training session is already finished", turnId = turnId))
            return
        }

        outbound.sendEvent(
            ChatStreamEvent(
                type = "session",
                sessionId = session.id,
                generationId = generationId,
                turnId = turnId,
            ),
        )
        sessionAnnounced.complete(Unit)

        commandJob = launch {
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val command = runCatching { streamingJson.decodeFromString<ChatStreamRequest>(frame.readText()) }.getOrNull()
                    if (command?.type == "cancel") {
                        manager.interrupt(session.id, generationId)
                        return@launch
                    }
                }
            } finally {
                if (!terminal.isCompleted) {
                    try {
                        manager.interrupt(session.id, generationId)
                    } catch (_: Exception) {
                        // Socket teardown is best-effort; the generation may already be gone.
                    }
                }
            }
        }

        when (val result = terminal.await()) {
            is ServerEvent.AssistantCompleted -> {
                textDeltas.close()
                ttsJob.join()
                tracker.mark("stream_completed")
                outbound.sendMetrics(tracker.finish("completed"))
                outbound.sendEvent(
                    ChatStreamEvent(
                        type = "done",
                        sessionId = session.id,
                        generationId = generationId,
                        turnId = turnId,
                    ),
                )
            }
            is ServerEvent.GenerationCancelled -> {
                textDeltas.close()
                ttsJob.cancelAndJoin()
                outbound.sendMetrics(tracker.finish("cancelled"))
                outbound.sendEvent(
                    ChatStreamEvent(
                        type = "cancelled",
                        sessionId = session.id,
                        generationId = generationId,
                        turnId = turnId,
                    ),
                )
            }
            is ServerEvent.Error -> {
                textDeltas.close()
                ttsJob.cancelAndJoin()
                outbound.sendMetrics(tracker.finish(result.code.lowercase()))
                outbound.sendEvent(
                    ChatStreamEvent(
                        type = "error",
                        sessionId = session.id,
                        generationId = generationId,
                        error = result.message,
                        turnId = turnId,
                    ),
                )
            }
            is ServerEvent.StaleGeneration -> {
                textDeltas.close()
                ttsJob.cancelAndJoin()
                outbound.sendMetrics(tracker.finish("stale_generation"))
                outbound.sendEvent(
                    ChatStreamEvent(
                        type = "error",
                        sessionId = session.id,
                        generationId = generationId,
                        error = "stale generation; current generation is ${result.currentGenerationId}",
                        turnId = turnId,
                    ),
                )
            }
            else -> Unit
        }
    } finally {
        textDeltas.close()
        ttsJob.cancelAndJoin()
        eventCollector.cancelAndJoin()
        commandJob?.cancelAndJoin()
        outbound.close()
        writer.join()
    }
}

private suspend fun WebSocketServerSession.receiveStart(): ChatStreamRequest? {
    val frame = incoming.receiveCatching().getOrNull()
    if (frame !is Frame.Text) {
        sendEvent(ChatStreamEvent("error", error = "start command is required"))
        return null
    }
    val start = runCatching { streamingJson.decodeFromString<ChatStreamRequest>(frame.readText()) }.getOrNull()
    if (start == null || start.type != "start" || start.message.isNullOrBlank()) {
        sendEvent(ChatStreamEvent("error", error = "start command with message is required"))
        return null
    }
    if (start.sessionId != null && runCatching { UUID.fromString(start.sessionId) }.isFailure) {
        sendEvent(ChatStreamEvent("error", error = "sessionId must be a UUID"))
        return null
    }
    if (start.turnId != null && runCatching { UUID.fromString(start.turnId) }.isFailure) {
        sendEvent(ChatStreamEvent("error", error = "turnId must be a UUID"))
        return null
    }
    if (start.generationId != null && start.generationId < 0) {
        sendEvent(ChatStreamEvent("error", error = "generationId must be non-negative"))
        return null
    }
    return start
}

private suspend fun SendChannel<Frame>.sendEvent(event: ChatStreamEvent) {
    send(Frame.Text(streamingJson.encodeToString(ChatStreamEvent.serializer(), event)))
}

private suspend fun WebSocketServerSession.sendEvent(event: ChatStreamEvent) {
    send(Frame.Text(streamingJson.encodeToString(ChatStreamEvent.serializer(), event)))
}

private suspend fun SendChannel<Frame>.sendMetrics(snapshot: ru.itimperial.speakingcharacter.service.TurnLatencySnapshot) {
    sendEvent(
        ChatStreamEvent(
            type = "metrics",
            turnId = snapshot.turnId,
            outcome = snapshot.outcome,
            metrics = snapshot.elapsedMillis,
        ),
    )
}
