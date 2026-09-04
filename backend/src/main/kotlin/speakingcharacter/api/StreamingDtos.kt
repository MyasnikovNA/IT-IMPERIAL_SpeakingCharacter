/** Описывает JSON-кадры браузерного WebSocket streaming протокола. */
package speakingcharacter.api

import kotlinx.serialization.Serializable

/** Команда браузера: начало нового хода либо отмена текущего. */
@Serializable
data class ChatStreamRequest(
    val type: String,
    val sessionId: String? = null,
    val message: String? = null,
)

/** Служебный text-кадр backend: session, delta, done либо error. */
@Serializable
data class ChatStreamEvent(
    val type: String,
    val sessionId: String? = null,
    val delta: String? = null,
    val error: String? = null,
)

/** Безопасный ответ выдачи Simli token без ключа API и Face ID. */
@Serializable
data class SimliSessionResponse(val token: String, val transport: String)
