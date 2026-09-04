/** Проверяет монотонное и идемпотентное измерение latency одного turn. */
package speakingcharacter.monitoring

import kotlin.test.Test
import kotlin.test.assertEquals

/** Тестирует независимость метрик от повторных сигналов одного этапа. */
class TurnLatencyTrackerTest {
    /** Сохраняет первый timestamp этапа и возвращает его в итоговом снимке. */
    @Test
    fun `tracker keeps first timestamp for each stage`() {
        var now = 1_000_000_000L
        val tracker = TurnLatencyTracker("00000000-0000-0000-0000-000000000001") { now }

        now += 25_000_000
        assertEquals(25, tracker.mark("input_received"))
        now += 100_000_000
        assertEquals(125, tracker.mark("gemini_first_delta"))
        now += 70_000_000
        assertEquals(125, tracker.mark("gemini_first_delta"))

        val snapshot = tracker.finish("completed")

        assertEquals("completed", snapshot.outcome)
        assertEquals(mapOf("gemini_first_delta" to 125L, "input_received" to 25L), snapshot.elapsedMillis)
    }
}
