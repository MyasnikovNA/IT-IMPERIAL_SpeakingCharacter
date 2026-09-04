/** Содержит небольшое число domain exceptions для lifecycle тренировочной сессии. */
package speakingcharacter.service

/** Сигнализирует, что запрошенная тренировочная сессия отсутствует. */
class SessionNotFoundException : RuntimeException("training session not found")

/** Сигнализирует о попытке изменить уже завершённую тренировку. */
class SessionFinishedException : RuntimeException("training session is already finished")

/** Сигнализирует о невалидном либо недоступном результате итоговой оценки. */
class EvaluationException(message: String) : RuntimeException(message)
