/** Возвращает true только для пользовательской роли из фактического JSON wire contract. */
export function isUserMessage(message) {
    return message?.role === "user";
}

/** Проверяет наличие корректного numeric generation id у evidence. */
export function hasEvidenceGenerationId(criterion) {
    return Number.isInteger(criterion?.evidenceGenerationId);
}

/** Строит уникальный DOM id для USER и MODEL сообщений одного turn. */
export function transcriptMessageDomId(message) {
    const prefix = isUserMessage(message) ? "user-message" : "trainer-message";
    return `${prefix}-${message?.generationId}`;
}

/** Возвращает anchor только для evidence, относящегося к реплике сотрудника. */
export function evidenceTargetId(criterion) {
    return hasEvidenceGenerationId(criterion) ? `user-message-${criterion.evidenceGenerationId}` : null;
}

/** Вычисляет компактную статистику по безопасной стенограмме результата. */
export function calculateTrainingStats(data) {
    const messages = data?.messages || [];
    return {
        duration: formatDuration(data?.createdAt, data?.finishedAt),
        userTurns: messages.filter(isUserMessage).length,
        interruptions: messages.filter((message) => !isUserMessage(message) && message.interrupted === true).length
    };
}

/** Форматирует длительность тренировки, не доверяя невалидным датам. */
export function formatDuration(createdAt, finishedAt) {
    const elapsed = new Date(finishedAt) - new Date(createdAt);
    if (!Number.isFinite(elapsed) || elapsed < 0) return "—";
    const seconds = Math.round(elapsed / 1000);
    return seconds < 60 ? `${seconds} сек.` : `${Math.floor(seconds / 60)} мин. ${seconds % 60} сек.`;
}

/** Готовит только безопасное состояние для повтора preset или свободной тренировки. */
export function buildRepeatScenarioState(result) {
    if (result?.scenarioSource === "UPLOADED") {
        return { selection: null, display: null, canRepeatDirectly: false };
    }
    if (result?.scenarioSource === "PRESET" && result.scenario) {
        return {
            selection: { presetId: result.scenario.id },
            display: {
                title: result.scenario.title,
                criteria: result.scenario.criteria || [],
                stageCount: result.scenario.stages?.length || 0
            },
            canRepeatDirectly: true
        };
    }
    return { selection: null, display: null, canRepeatDirectly: true };
}

/** Классифицирует lifecycle отчёта и отделяет ACTIVE от состояния polling. */
export function classifyReportState(result) {
    if (!result) return "ERROR";
    if (result.status === "ACTIVE") return "ACTIVE";
    switch (result.reportStatus) {
        case "READY": return result.report ? "READY" : "ERROR";
        case "FAILED": return "FAILED";
        case "GENERATING":
        case "NOT_STARTED": return "PENDING";
        default: return "ERROR";
    }
}
