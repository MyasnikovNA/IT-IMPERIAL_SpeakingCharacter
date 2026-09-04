/** Проверяет разбор streaming-данных без внешних Gemini и ElevenLabs. */
package speakingcharacter.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import speakingcharacter.config.AppConfig

/** Тестирует SSE-дельты Gemini, word-safe буфер и Base64 PCM relay. */
class StreamingSupportTest {
    /** Извлекает только видимый текст из одного Gemini SSE JSON-события. */
    @Test
    fun `Gemini SSE parser extracts visible text delta`() {
        val gemini = GeminiClient(HttpClient(MockEngine { error("HTTP не должен вызываться") }), testConfig())

        val delta = gemini.extractSseTextDelta(
            """{"candidates":[{"content":{"parts":[{"text":"Привет"},{"thought":true,"text":"скрыто"},{"text":"!"}]}}]}""",
        )

        assertEquals("Привет!", delta)
    }

    /** Отклоняет повреждённый SSE JSON до передачи его в TTS. */
    @Test
    fun `Gemini SSE parser rejects malformed event`() {
        val gemini = GeminiClient(HttpClient(MockEngine { error("HTTP не должен вызываться") }), testConfig())

        assertFailsWith<GeminiException> { gemini.extractSseTextDelta("not json") }
    }

    /** Не передаёт незавершённое слово при достижении char-лимита. */
    @Test
    fun `word safe buffer waits for a word boundary`() {
        val buffer = WordSafeTextBuffer(10)

        assertEquals(emptyList(), buffer.append("Добрый ден"))
        assertEquals(listOf("Добрый день "), buffer.append("ь коллеги"))
        assertEquals("коллеги", buffer.finish())
    }

    /** Сразу передаёт завершённую фразу, даже если она короче обычного окна. */
    @Test
    fun `word safe buffer flushes completed sentence`() {
        val buffer = WordSafeTextBuffer(50)

        assertEquals(listOf("Да."), buffer.append("Да."))
        assertEquals("", buffer.finish())
    }

    /** Декодирует Base64 ElevenLabs audio в исходный PCM16 без преобразования байтов. */
    @Test
    fun `ElevenLabs frame relay decodes PCM bytes`() {
        val tts = ElevenLabsStreamingTtsClient(HttpClient(MockEngine { error("HTTP не должен вызываться") }), testConfig())

        val frame = tts.parseAudio("""{"audio":"AAH//g==","isFinal":false}""")

        assertContentEquals(byteArrayOf(0, 1, -1, -2), frame.pcm)
        assertEquals(false, frame.isFinal)
    }

    /** Создаёт минимальную конфигурацию для изолированных streaming-тестов. */
    private fun testConfig(): AppConfig = AppConfig(
        geminiApiKey = "key",
        geminiModel = "model",
        databaseUrl = "jdbc:postgresql://unused",
        databaseUser = "unused",
        databasePassword = "unused",
        maxContextMessages = 20,
        frontendHost = "localhost:8000",
    )
}
