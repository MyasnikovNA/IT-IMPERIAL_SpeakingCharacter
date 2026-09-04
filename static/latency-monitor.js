/** Собирает browser latency-метрики одного turn на монотонных performance clocks. */
export const latencyTargets = Object.freeze({
    firstResponseAudioMs: 3_000,
    avatarSyncMs: 200,
    interruptionMs: 300
});

/** Создаёт correlation-id и точку отсчёта одного пользовательского turn. */
export function createLatencyTurn(now = performance.now(), turnId = createTurnId()) {

    return { turnId, startedAt: now, marks: {}, reported: false };

}

/** Фиксирует первый наступивший этап относительно отправки user input. */
export function markLatency(turn, stage, now = performance.now()) {

    if (turn.marks[stage] === undefined) {
        turn.marks[stage] = Math.max(0, Math.round(now - turn.startedAt));
    }
    return turn.marks[stage];

}

/** Формирует безопасный отчёт и проверяет целевые значения SLA на browser участке. */
export function buildLatencyReport(turn, outcome) {

    const metrics = { ...turn.marks };
    const firstAudio = metrics.simli_speaking;
    const sync = firstAudio !== undefined && metrics.browser_first_pcm !== undefined
        ? Math.max(0, firstAudio - metrics.browser_first_pcm)
        : undefined;
    const interruption = metrics.simli_silent !== undefined && metrics.interruption_requested !== undefined
        ? Math.max(0, metrics.simli_silent - metrics.interruption_requested)
        : undefined;

    if (sync !== undefined) {
        metrics.pcm_to_avatar_speaking_ms = sync;
    }
    if (interruption !== undefined) {
        metrics.interruption_to_silent_ms = interruption;
    }

    return {
        turnId: turn.turnId,
        outcome,
        metrics,
        slo: {
            first_response_audio: firstAudio !== undefined && firstAudio <= latencyTargets.firstResponseAudioMs,
            avatar_sync_proxy: sync !== undefined && sync <= latencyTargets.avatarSyncMs,
            interruption: interruption !== undefined && interruption <= latencyTargets.interruptionMs
        }
    };
}

/** Отправляет только агрегированные числа в backend; ошибка telemetry не влияет на разговор. */
export function reportLatency(chatApiUrl, report, sessionId) {

    return fetch(`${chatApiUrl}/api/metrics`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        keepalive: true,
        body: JSON.stringify({ ...report, sessionId })
    });

}

/** Генерирует UUID браузера с небольшим fallback для старых окружений. */
function createTurnId() {

    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID();
    }
    return "00000000-0000-4000-8000-" + Date.now().toString(16).padStart(12, "0");

}
