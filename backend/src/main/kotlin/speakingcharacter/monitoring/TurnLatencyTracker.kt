/** Собирает монотонные latency-метрики одного потокового хода без текста и секретов. */
package speakingcharacter.monitoring

import java.util.Collections
import org.slf4j.LoggerFactory

/** Неизменяемый снимок latency одного хода, пригодный для structured logging и browser relay. */
data class TurnLatencySnapshot(
    val turnId: String,
    val outcome: String,
    val elapsedMillis: Map<String, Long>,
)

/**
 * Измеряет этапы одного turn через `System.nanoTime`, поэтому изменения системных часов не искажают latency.
 * Один этап фиксируется только один раз, даже если его замечают две coroutine одновременно.
 */
class TurnLatencyTracker(
    private val turnId: String,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(TurnLatencyTracker::class.java)
    private val startedAt = nanoTime()
    private val lock = Any()
    private val stages = mutableMapOf<String, Long>()
    private var snapshot: TurnLatencySnapshot? = null

    /** Фиксирует elapsed time этапа относительно получения user input до завершения snapshot. */
    fun mark(stage: String): Long = synchronized(lock) {
        require(stage.isNotBlank()) { "stage must not be blank" }
        snapshot?.let { return@synchronized it.elapsedMillis[stage] ?: -1L }
        val elapsed = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
        val previous = stages[stage]
        if (previous == null) {
            stages[stage] = elapsed
            logger.info("turn_latency_stage turnId={} stage={} elapsedMs={}", turnId, stage, elapsed)
            return elapsed
        }
        return previous
    }

    /** Однократно фиксирует outcome и immutable snapshot, возвращая его всем конкурентным вызовам. */
    fun finish(outcome: String): TurnLatencySnapshot = synchronized(lock) {
        snapshot?.let { return@synchronized it }
        val frozen = TurnLatencySnapshot(turnId, outcome, Collections.unmodifiableMap(stages.toSortedMap()))
        snapshot = frozen
        logger.info("turn_latency_summary turnId={} outcome={} stages={}", turnId, outcome, frozen.elapsedMillis)
        frozen
    }
}
