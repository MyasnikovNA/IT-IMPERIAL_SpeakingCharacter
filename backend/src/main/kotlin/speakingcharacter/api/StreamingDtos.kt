/** Описывает JSON-кадры браузерного WebSocket streaming протокола. */
package speakingcharacter.api

import kotlinx.serialization.Serializable

/** Команда браузера: начало нового хода либо отмена текущего. */
@Serializable
data class ChatStreamRequest(
    val type: String,
    val sessionId: String? = null,
    val message: String? = null,
    val turnId: String? = null,
)

/** Служебный text-кадр backend: session, delta, metrics, done либо error. */
@Serializable
data class ChatStreamEvent(
    val type: String,
    val sessionId: String? = null,
    val delta: String? = null,
    val error: String? = null,
    val turnId: String? = null,
    val outcome: String? = null,
    val metrics: Map<String, Long>? = null,
    val frameId: Long? = null,
    val cues: List<SubtitleCueEvent>? = null,
)

/** Один serializable subtitle cue, привязанный к началу соответствующего PCM-кадра. */
@Serializable
data class SubtitleCueEvent(val text: String, val startMs: Long, val endMs: Long)

/** Метрики браузерного участка одного turn без transcript, аудио и иных чувствительных данных. */
@Serializable
data class BrowserLatencyMetricsRequest(
    val turnId: String,
    val sessionId: String? = null,
    val outcome: String,
    val metrics: Map<String, Long>,
    val slo: Map<String, Boolean>,
)

/** Безопасный ответ выдачи Simli token без ключа API и Face ID. */
@Serializable
data class SimliSessionResponse(val token: String, val transport: String)
