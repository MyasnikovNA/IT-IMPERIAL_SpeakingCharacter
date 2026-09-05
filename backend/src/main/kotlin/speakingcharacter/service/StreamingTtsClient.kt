/** Определяет потоковый синтез речи и alignment для avatar transport и субтитров. */
package speakingcharacter.service

import kotlinx.coroutines.flow.Flow

/** Преобразует последовательность текстовых дельт в PCM16 аудиофреймы. */
interface StreamingTtsClient {
    /** Синтезирует речь из текстовых дельт и выдаёт PCM с alignment в порядке получения. */
    fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame>
}

/** Один PCM16 фрейм и необязательное выравнивание символов относительно его начала. */
data class TtsAudioFrame(
    val pcm: ByteArray,
    val alignment: TtsAlignment? = null,
    val isFinal: Boolean = false,
)

/** Таймкоды символов, полученные от TTS-провайдера для одного аудиофрейма. */
data class TtsAlignment(
    val chars: List<String>,
    val charStartTimesMs: List<Long>,
    val charDurationsMs: List<Long>,
)

/** Короткая фраза субтитра с временным окном относительно начала аудиофрейма. */
data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

/** Строит ограниченные phrase-level cues из character alignment без изменения аудиопотока. */
object SubtitleCueBuilder {
    private const val MAX_VISIBLE_CHARACTERS = 42

    /** Возвращает непустые фразы, завершаемые на границе слова или пунктуации. */
    fun build(alignment: TtsAlignment?): List<SubtitleCue> {
        if (alignment == null || !alignment.isValid()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        val text = StringBuilder()
        var startMs = 0L
        var endMs = 0L
        alignment.chars.forEachIndexed { index, character ->
            if (text.isEmpty()) startMs = alignment.charStartTimesMs[index]
            text.append(character)
            endMs = alignment.charStartTimesMs[index] + alignment.charDurationsMs[index]
            if (isCueBoundary(character) && text.count { !it.isWhitespace() } >= MAX_VISIBLE_CHARACTERS) {
                addCue(cues, text, startMs, endMs)
            }
        }
        addCue(cues, text, startMs, endMs)
        return cues
    }

    /** Проверяет согласованность и неотрицательность временных массивов alignment. */
    private fun TtsAlignment.isValid(): Boolean = chars.isNotEmpty() &&
        chars.size == charStartTimesMs.size &&
        chars.size == charDurationsMs.size &&
        charStartTimesMs.zip(charDurationsMs).all { (startMs, durationMs) -> startMs >= 0 && durationMs >= 0 }

    /** Определяет безопасную границу фразы без разрыва слова. */
    private fun isCueBoundary(character: String): Boolean = character.any { it.isWhitespace() || it in ".,!?:;…" }

    /** Добавляет нормализованную фразу, если в ней есть видимый текст. */
    private fun addCue(cues: MutableList<SubtitleCue>, text: StringBuilder, startMs: Long, endMs: Long) {
        val value = text.toString().trim()
        text.clear()
        if (value.isNotEmpty()) cues += SubtitleCue(value, startMs, endMs.coerceAtLeast(startMs))
    }
}

/** Буферизует текст только до границы слова, не разрывая его между TTS-запросами. */
class WordSafeTextBuffer(private val minimumCharacters: Int) {
    private val buffer = StringBuilder()

    init {
        require(minimumCharacters > 0) { "minimumCharacters must be positive" }
    }

    /** Добавляет Gemini-дельту и возвращает готовые фрагменты для ElevenLabs. */
    fun append(delta: String): List<String> {
        buffer.append(delta)
        val sentenceBoundary = buffer.indexOfLast { it == '.' || it == '!' || it == '?' || it == '…' }
        val wordBoundary = buffer.indexOfLast { it.isWhitespace() }
        val boundary = when {
            sentenceBoundary >= 0 -> sentenceBoundary + 1
            wordBoundary >= 0 && wordBoundary + 1 >= minimumCharacters -> wordBoundary + 1
            else -> -1
        }
        return if (boundary > 0) listOf(take(boundary)) else emptyList()
    }

    /** Возвращает остаток текста при нормальном завершении Gemini stream. */
    fun finish(): String = take(buffer.length)

    /** Извлекает заданный префикс, сохраняя порядок и пробелы между словами. */
    private fun take(length: Int): String = buffer.substring(0, length).also { buffer.delete(0, length) }
}
