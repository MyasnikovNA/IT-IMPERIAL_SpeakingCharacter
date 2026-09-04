/** Определяет потоковый синтез речи для передачи PCM16 в avatar transport. */
package speakingcharacter.service

import kotlinx.coroutines.flow.Flow

/** Преобразует последовательность текстовых дельт в PCM16 аудиофреймы. */
interface StreamingTtsClient {
    /** Синтезирует речь из текстовых дельт и выдаёт фреймы в порядке получения. */
    fun synthesize(textDeltas: Flow<String>): Flow<ByteArray>
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
