const id = new URLSearchParams(location.search).get("sessionId");
const byId = (value) => document.getElementById(value);
const title = byId("report-title");
const date = byId("report-date");
const loading = byId("report-loading");
const failure = byId("report-failure");
const content = byId("report-content");
const transcript = byId("transcript");
let config;
let result;

/** Безопасно создаёт текстовый DOM-элемент без интерполяции ответа модели. */
function textElement(tag, value, className = "") { const element = document.createElement(tag); element.textContent = value; if (className) element.className = className; return element; }
function safeScenarioSelection(data) { return data.scenarioSource === "PRESET" && data.scenario ? { presetId: data.scenario.id } : null; }
function formatDuration(createdAt, finishedAt) {
    if (!createdAt || !finishedAt) return "—";
    const seconds = Math.max(0, Math.round((new Date(finishedAt) - new Date(createdAt)) / 1000));
    return seconds < 60 ? `${seconds} сек.` : `${Math.floor(seconds / 60)} мин. ${seconds % 60} сек.`;
}
function showStats(data) {
    const stats = byId("training-stats");
    const userMessages = (data.messages || []).filter((message) => message.role === "USER").length;
    const rows = [["Длительность", formatDuration(data.createdAt, data.finishedAt)], ["Реплик сотрудника", String(userMessages)]];
    stats.replaceChildren(...rows.flatMap(([label, value]) => [textElement("dt", label), textElement("dd", value)]));
}
function showTranscript(messages) {
    transcript.replaceChildren(...messages.map((message) => {
        const row = document.createElement("article");
        row.className = `transcript__row transcript__row--${message.role === "USER" ? "user" : "trainer"}`;
        row.id = `message-${message.generationId}`;
        row.append(textElement("strong", message.role === "USER" ? "Вы" : "Тренер"));
        row.append(textElement("p", message.text + (message.interrupted ? " (реплика прервана)" : "")));
        return row;
    }));
}
function showReady(data) {
    const report = data.report;
    loading.hidden = true; failure.hidden = true; content.hidden = false;
    byId("overall-score").textContent = String(report.overallScore);
    byId("report-summary").textContent = report.summary;
    const criteria = byId("criteria-list"); criteria.replaceChildren(...report.criteria.map((criterion) => {
        const card = document.createElement("article"); card.className = "criterion-card";
        const heading = textElement("h3", `${criterion.name} — ${criterion.score}/5`);
        const comment = textElement("p", criterion.comment);
        const evidence = textElement("blockquote", `«${criterion.evidence}»`);
        card.append(heading, comment, evidence);
        if (criterion.evidenceGenerationId !== null) {
            const link = document.createElement("a"); link.href = `#message-${criterion.evidenceGenerationId}`; link.textContent = "Показать ответ в стенограмме"; card.append(link);
        }
        return card;
    }));
    byId("recommendations").replaceChildren(...report.recommendations.map((item) => textElement("li", item)));
    showStats(data);
}
function showFailure(data) { loading.hidden = true; failure.hidden = false; content.hidden = true; byId("report-error").textContent = data.reportError || "Не удалось сформировать оценку. Попробуйте ещё раз."; }
/** Загружает только safe result DTO: инструкции и exit rules никогда не попадают в report page. */
async function loadResult() {
    const response = await fetch(`${config.chat_api_url}/api/sessions/${encodeURIComponent(id)}/result`);
    if (!response.ok) throw new Error("Тренировка не найдена");
    result = await response.json();
    title.textContent = result.scenario?.title || "Свободная тренировка";
    date.textContent = result.finishedAt ? `Завершена: ${new Date(result.finishedAt).toLocaleString("ru-RU")}` : "Тренировка сохранена";
    showTranscript(result.messages || []);
    if (result.reportStatus === "READY" && result.report) showReady(result); else if (result.reportStatus === "FAILED") showFailure(result); else { loading.hidden = false; setTimeout(loadResult, 1500); }
}
byId("retry-report").addEventListener("click", async () => { byId("retry-report").disabled = true; loading.hidden = false; failure.hidden = true; await fetch(`${config.chat_api_url}/api/sessions/${encodeURIComponent(id)}/report/retry`, { method: "POST" }); await loadResult(); });
byId("repeat-scenario").addEventListener("click", () => {
    const selection = safeScenarioSelection(result);
    if (!selection) { location.assign("/"); return; }
    sessionStorage.setItem("speaking-character.scenario-selection", JSON.stringify(selection));
    location.assign("/training.html");
});
byId("save-report").addEventListener("click", () => window.print());
try { if (!id) throw new Error("Не указан идентификатор тренировки"); config = await (await fetch("/api/config")).json(); await loadResult(); } catch (error) { loading.hidden = true; failure.hidden = false; byId("report-error").textContent = error.message; byId("retry-report").hidden = true; }
