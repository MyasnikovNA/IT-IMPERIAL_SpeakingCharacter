/** Определяет persistence-операции структурированных отчётов тренировки. */
package speakingcharacter.db

import speakingcharacter.model.TrainingReport
import java.util.UUID

/** Минимальный контракт получения и атомарного сохранения итоговых отчётов. */
interface EvaluationRepository {
    /** Возвращает сохранённый report с критериями или null. */
    fun findReport(sessionId: UUID): TrainingReport?

    /** Сохраняет report, его criteria и завершает session одной транзакцией. */
    fun saveReportAndFinishSession(report: TrainingReport): TrainingReport
}
