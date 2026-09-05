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

        val frame = tts.parseAudio(
            """{"audio":"AAH//g==","is_final":false,"alignment":{"chars":["Д","а"," "],"char_start_times_ms":[0,90,180],"char_durations_ms":[90,90,30]}}""",
        )

        assertContentEquals(byteArrayOf(0, 1, -1, -2), frame.pcm)
        assertEquals(false, frame.isFinal)
        assertEquals(listOf("Д", "а", " "), frame.alignment?.chars)
    }

    /** Формирует короткие phrase-level cues без разрыва русских слов. */
    @Test
    fun `subtitle cue builder keeps Russian words intact`() {
        val cues = SubtitleCueBuilder.build(
            TtsAlignment(
                chars = "Добрый день".map(Char::toString),
                charStartTimesMs = listOf(0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500),
                charDurationsMs = List(11) { 50 },
            ),
        )

        assertEquals(listOf(SubtitleCue("Добрый день", 0, 550)), cues)
    }

    /** Не создаёт cue для повреждённого alignment, не блокируя аудиорелей. */
    @Test
    fun `subtitle cue builder ignores inconsistent alignment`() {
        val cues = SubtitleCueBuilder.build(TtsAlignment(listOf("А"), emptyList(), emptyList()))

        assertEquals(emptyList(), cues)
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
