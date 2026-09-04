/** Собирает монотонные latency-метрики одного потокового хода без текста и секретов. */
package speakingcharacter.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val stages = ConcurrentHashMap<String, Long>()
    private val finished = AtomicBoolean(false)

    /** Фиксирует elapsed time этапа относительно получения user input и возвращает его в миллисекундах. */
    fun mark(stage: String): Long {
        require(stage.isNotBlank()) { "stage must not be blank" }
        val elapsed = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
        val previous = stages.putIfAbsent(stage, elapsed)
        if (previous == null) {
            logger.info("turn_latency_stage turnId={} stage={} elapsedMs={}", turnId, stage, elapsed)
            return elapsed
        }
        return previous
    }

    /** Завершает измерение, однократно логирует structured snapshot и возвращает его вызывающему коду. */
    fun finish(outcome: String): TurnLatencySnapshot {
        val snapshot = TurnLatencySnapshot(turnId, outcome, stages.toSortedMap())
        if (finished.compareAndSet(false, true)) {
            logger.info("turn_latency_summary turnId={} outcome={} stages={}", turnId, outcome, snapshot.elapsedMillis)
        }
        return snapshot
    }
}
