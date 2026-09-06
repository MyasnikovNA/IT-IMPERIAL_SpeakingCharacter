const SELECTION_KEY = "speaking-character.scenario-selection";
const MAX_SCENARIO_BYTES = 32 * 1024;
const preset = document.getElementById("scenario-preset");
const file = document.getElementById("scenario-file");
const fileName = document.getElementById("scenario-file-name");
const status = document.getElementById("scenario-status");
const start = document.getElementById("start-training");
let chatApiUrl = null;
let selectedScenario = null;
let fileValidationPending = false;

// Главная страница всегда начинает новую тренировку и не наследует выбор прошлой.
sessionStorage.removeItem(SELECTION_KEY);

/** Показывает безопасный статус выбора сценария без вставки Markdown в DOM. */
function setStatus(message, state = "") { status.textContent = message; status.dataset.state = state; }
/** Сохраняет только валидированный выбор до первой реплики новой тренировки. */
function persistSelection() { if (selectedScenario) sessionStorage.setItem(SELECTION_KEY, JSON.stringify(selectedScenario)); else sessionStorage.removeItem(SELECTION_KEY); }
/** Получает публичную конфигурацию API без provider keys. */
async function loadConfig() { const response = await fetch("/api/config"); if (!response.ok) throw new Error("Не удалось получить конфигурацию приложения"); return response.json(); }
/** Загружает безопасные карточки preset-сценариев. */
async function loadPresets() { const response = await fetch(`${chatApiUrl}/api/scenarios`); if (!response.ok) throw new Error("Не удалось загрузить готовые сценарии"); for (const scenario of await response.json()) { const option = document.createElement("option"); option.value = scenario.id; option.textContent = scenario.title; preset.append(option); } preset.disabled = false; }
/** Выбирает preset или свободный диалог. */
function selectPreset() { file.value = ""; fileName.textContent = "Markdown-файл до 32 КБ"; selectedScenario = preset.value ? { presetId: preset.value } : null; persistSelection(); setStatus(selectedScenario ? "Сценарий будет закреплён после первой реплики." : "Можно начать свободный диалог без сценария.", "success"); }
/** Валидирует пользовательский Markdown до старта тренировки. */
async function selectFile() { const selectedFile = file.files?.[0]; if (!selectedFile) return; if (!selectedFile.name.toLowerCase().endsWith(".md")) { file.value = ""; setStatus("Поддерживается только Markdown-файл с расширением .md.", "error"); return; } if (selectedFile.size > MAX_SCENARIO_BYTES) { file.value = ""; setStatus("Размер сценария не должен превышать 32 КБ.", "error"); return; } try { fileValidationPending = true; setStatus("Проверяем сценарий…"); const markdown = await selectedFile.text(); const response = await fetch(`${chatApiUrl}/api/scenarios/validate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ markdown }) }); const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.error || "Сценарий не прошёл проверку"); selectedScenario = { markdown }; preset.value = ""; fileName.textContent = `${selectedFile.name}: ${payload.title || "сценарий проверен"}`; persistSelection(); setStatus(`Готово: ${payload.stages?.length ?? 0} этапов и ${payload.criteria?.length ?? 0} критерия.`, "success"); } catch (error) { selectedScenario = null; file.value = ""; persistSelection(); setStatus(error.message, "error"); } finally { fileValidationPending = false; } }
preset.addEventListener("change", selectPreset); file.addEventListener("change", selectFile);
start.addEventListener("click", (event) => { if (fileValidationPending) { event.preventDefault(); setStatus("Дождитесь проверки Markdown-файла.", "error"); } });
try { const config = await loadConfig(); chatApiUrl = config.chat_api_url; await loadPresets(); } catch (error) { file.disabled = true; setStatus(`${error.message}. Свободный диалог всё ещё доступен.`, "error"); }
