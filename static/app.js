import { openSimliStream } from "./simli-stream-client.js";
import { buildLatencyReport, createLatencyTurn, markLatency, reportLatency } from "./latency-monitor.js";

const video =
    document.getElementById("avatar");

const audio =
    document.getElementById("avatar-audio");

const text =
    document.getElementById("text");

const connectButton =
    document.getElementById("connect");

const speakButton =
    document.getElementById("speak");

const disconnectButton =
    document.getElementById("disconnect");

const status =
    document.getElementById("status");

const trainingScenario =
    document.getElementById("training-scenario");


let agentManager = null;
let simliClient = null;
let simliConnected = false;
let streamRelay = null;
let avatarSpeaking = false;
let simliSessionStartedAt = null;
let simliSpeakingStartedAt = null;
let simliSpeakingTotalMs = 0;
let activeLatencyTurn = null;
let pendingInterruptedTurn = null;
let interruptionTimer = null;
let chatSessionId = null;
let appConfig = null;
const pageStartedAt = performance.now();
const scenarioSelection = readScenarioSelection();

/** Читает одноразовый сценарий, выбранный до создания новой сессии. */
function readScenarioSelection() {

    try {

        const raw = sessionStorage.getItem("speaking-character.scenario-selection");
        if (!raw) {
            return null;
        }

        const selection = JSON.parse(raw);
        const hasPreset = typeof selection.presetId === "string" && selection.presetId.length > 0;
        const hasMarkdown = typeof selection.markdown === "string" && selection.markdown.length > 0;
        if (hasPreset === hasMarkdown) {
            sessionStorage.removeItem("speaking-character.scenario-selection");
            return null;
        }

        if (hasMarkdown && selection.markdown.length > 32 * 1024) {
            sessionStorage.removeItem("speaking-character.scenario-selection");
            return null;
        }

        return hasPreset ? { presetId: selection.presetId } : { markdown: selection.markdown };

    } catch {

        sessionStorage.removeItem("speaking-character.scenario-selection");
        return null;

    }

}

/** Возвращает сценарий исключительно для первого хода новой тренировки. */
function scenarioForNewSession() {

    return chatSessionId === null ? scenarioSelection : null;

}

/** Показывает выбранный режим, не выводя содержимое пользовательского Markdown. */
function renderScenarioLabel() {

    if (!trainingScenario) {
        return;
    }

    trainingScenario.textContent = scenarioSelection
        ? scenarioSelection.presetId
            ? `Сценарий: ${scenarioSelection.presetId}`
            : "Сценарий: загруженный Markdown"
        : "Свободный диалог";

}

/** Логирует измерение пользовательского пути без содержимого сообщений и секретов. */
function logTiming(event, startedAt, details = {}) {

    console.info("[timing]", {
        event,
        durationMs: Math.round(performance.now() - startedAt),
        sincePageStartMs: Math.round(performance.now() - pageStartedAt),
        ...details
    });

}

/** Фиксирует browser latency stage и не пишет текст пользовательской реплики в консоль. */
function markTurnLatency(turn, stage) {

    const elapsedMs = markLatency(turn, stage);
    console.info("[latency]", { turnId: turn.turnId, stage, elapsedMs });
    return elapsedMs;

}

/** Публикует завершённый browser-отчёт; telemetry не должна влиять на разговор. */
function finishTurnLatency(turn, config, outcome) {

    if (!turn || turn.reported) {
        return;
    }

    turn.reported = true;
    const report = buildLatencyReport(turn, outcome);
    console.info("[latency]", { turnId: report.turnId, outcome, metrics: report.metrics, slo: report.slo });
    reportLatency(config.chat_api_url, report, turn.sessionId || chatSessionId)
        .catch(() => console.warn("Не удалось передать latency-метрики"));

}

/** Отменяет текущую речь перед новым вводом и начинает измерение target 300 мс. */
function interruptActiveTurn(config) {

    if (!activeLatencyTurn) {
        return;
    }

    const interruptedTurn = activeLatencyTurn;
    interruptedTurn.interrupted = true;
    markTurnLatency(interruptedTurn, "interruption_requested");
    pendingInterruptedTurn = interruptedTurn;
    activeLatencyTurn = null;

    if (streamRelay) {
        streamRelay.cancel();
        streamRelay = null;
    }
    simliClient?.ClearBuffer();
    clearTimeout(interruptionTimer);
    interruptionTimer = window.setTimeout(() => {
        if (pendingInterruptedTurn === interruptedTurn) {
            finishTurnLatency(interruptedTurn, config, "interruption_timeout");
            pendingInterruptedTurn = null;
        }
    }, 300);

}

/** Логирует переходы HTML video, чтобы отделить WebRTC от загрузки и воспроизведения. */
["loadedmetadata", "canplay", "playing", "waiting", "stalled", "ended"].forEach((event) => {

    video.addEventListener(event, () => {

        console.info("[timing]", {
            event: `video_${event}`,
            sincePageStartMs: Math.round(performance.now() - pageStartedAt),
            readyState: video.readyState,
            networkState: video.networkState
        });

    });

});

/** Обновляет отображаемый статус подключения или запроса. */
function setStatus(message) {

    status.textContent = message;

}

/** Загружает и кэширует безопасную публичную конфигурацию выбранного аватара. */
async function loadConfig() {

    if (appConfig) {

        return appConfig;

    }

    const startedAt = performance.now();
    const response =
        await fetch("/api/config");

    logTiming("config_response", startedAt, { status: response.status });

    if (!response.ok) {

        throw new Error(
            "Не удалось получить конфигурацию"
        );

    }

    appConfig = await response.json();

    return appConfig;

}

/** Подключает выбранный провайдер аватара и включает элементы управления диалогом. */
async function connect() {

    try {

        const connectStartedAt = performance.now();

        setStatus(
            "Подключение к аватару..."
        );

        connectButton.disabled = true;


        const config =
            await loadConfig();

        if (config.avatar_provider === "simli") {
            await connectSimli(config, connectStartedAt);
            return;
        }


        const callbacks = {

            /**
             * Прикрепляет готовый WebRTC-поток к элементу видео аватара.
             * @param {MediaStream} stream Поток, полученный от D-ID.
             */
            onSrcObjectReady(stream) {

                console.log(
                    "WebRTC stream ready"
                );

                video.srcObject =
                    stream;

                video.play()
                    .catch(console.error);

            },


            /**
             * Отображает изменение состояния подключения D-ID.
             * @param {string} state Новое состояние подключения.
             */
            onConnectionStateChange(state) {

                console.log(
                    "Connection:",
                    state
                );

                setStatus(
                    "Соединение: " + state
                );

            },


            /**
             * Логирует служебное сообщение, полученное от D-ID.
             * @param {unknown} messages Данные сообщения D-ID.
             * @param {string} type Тип сообщения.
             */
            onNewMessage(messages, type) {

                console.log(
                    "Message:",
                    messages,
                    type
                );

            },


            /**
             * Логирует ошибку D-ID и показывает пользователю безопасный статус.
             * @param {unknown} error Основная ошибка D-ID.
             * @param {unknown} errorData Дополнительные данные ошибки.
             */
            onError(error, errorData) {

                console.error(
                    "D-ID error:",
                    error,
                    errorData
                );

                setStatus(
                    "Ошибка D-ID"
                );

            }

        };


        const managerStartedAt = performance.now();
        const did =
            await import("https://cdn.jsdelivr.net/npm/@d-id/client-sdk/+esm");

        agentManager =
            await did.createAgentManager(

                config.agent_id,

                {

                    auth: {

                        type: "key",

                        clientKey:
                            config.client_key

                    },


                    callbacks,


                    streamOptions: {

                        compatibilityMode:
                            "auto",

                        streamWarmup:
                            true

                    }

                }

            );
        logTiming("did_manager_created", managerStartedAt);


        const didConnectStartedAt = performance.now();
        await agentManager.connect();
        logTiming("did_connect_completed", didConnectStartedAt);
        logTiming("avatar_connect_total", connectStartedAt);


        setStatus(
            "Аватар подключён"
        );


        speakButton.disabled =
            false;

        disconnectButton.disabled =
            false;


    }
    catch (error) {

        console.error(error);

        setStatus(
            "Ошибка подключения: "
            + error.message
        );

        connectButton.disabled =
            false;

    }

}

/** Подключает Simli по token, не получая API key и Face ID в браузер. */
async function connectSimli(config, connectStartedAt) {

    setStatus("Подключение к Simli...");
    simliConnected = false;
    const tokenStartedAt = performance.now();
    const response = await fetch(`${config.chat_api_url}/api/avatar/simli/session`, { method: "POST" });
    logTiming("simli_session_token_response", tokenStartedAt, { status: response.status });
    const payload = await response.json().catch(() => ({}));

    if (!response.ok) {
        throw new Error(payload.error || "Не удалось открыть сессию Simli");
    }

    const simliModule =
        await import("/vendor/simli-client.js");
    const { LogLevel, SimliClient } =
        simliModule.default;

    simliClient = new SimliClient(
        payload.token,
        video,
        audio,
        null,
        LogLevel.INFO,
        payload.transport
    );
    simliClient.on("start", () => {
        simliConnected = true;
        simliSessionStartedAt = performance.now();
        logTiming("simli_start", connectStartedAt);
        setStatus("Аватар подключён");
    });
    simliClient.on("speaking", () => {
        avatarSpeaking = true;
        simliSpeakingStartedAt = performance.now();
        if (activeLatencyTurn) {
            markTurnLatency(activeLatencyTurn, "simli_speaking");
        }
        logTiming("simli_speaking", simliSessionStartedAt || connectStartedAt);
        setStatus("Аватар говорит...");
    });
    simliClient.on("silent", () => {
        avatarSpeaking = false;
        if (simliSpeakingStartedAt) {
            simliSpeakingTotalMs += performance.now() - simliSpeakingStartedAt;
            logTiming("simli_silent", simliSpeakingStartedAt, {
                speakingTotalMs: Math.round(simliSpeakingTotalMs)
            });
            simliSpeakingStartedAt = null;
        }
        if (pendingInterruptedTurn) {
            markTurnLatency(pendingInterruptedTurn, "simli_silent");
            finishTurnLatency(pendingInterruptedTurn, config, "interrupted");
            pendingInterruptedTurn = null;
            clearTimeout(interruptionTimer);
        } else if (activeLatencyTurn && !streamRelay) {
            markTurnLatency(activeLatencyTurn, "simli_silent");
            finishTurnLatency(activeLatencyTurn, config, "completed");
            activeLatencyTurn = null;
        }
        if (!streamRelay) {
            setStatus("Готов");
            speakButton.disabled = false;
        }
    });
    simliClient.on("ack", () => console.info("[timing]", { event: "simli_ack" }));
    simliClient.on("stop", () => handleSimliTransportStopped("Сессия Simli завершилась из-за неактивности. Подключите аватара заново."));
    simliClient.on("startup_error", (error) => {
        console.error("Simli startup error", error);
        handleSimliTransportStopped("Не удалось запустить Simli. Подключите аватара заново.");
    });
    simliClient.on("error", (error) => {
        console.error("Simli error", error);
        handleSimliTransportStopped("Соединение с Simli потеряно. Подключите аватара заново.");
    });

    const startedAt = performance.now();
    await simliClient.start();
    logTiming("simli_connect_completed", startedAt);
    logTiming("avatar_connect_total", connectStartedAt, { provider: "simli" });
    speakButton.disabled = false;
    disconnectButton.disabled = false;
}

/** Возвращает UI в состояние переподключения после idle timeout или ошибки Simli. */
function handleSimliTransportStopped(message) {

    if (!simliClient && !simliConnected) {
        return;
    }

    simliConnected = false;
    avatarSpeaking = false;
    if (streamRelay) {
        try {
            streamRelay.cancel();
        } catch (error) {
            console.warn("Не удалось отменить завершённый поток Simli", error);
        }
        streamRelay = null;
    }
    simliClient = null;
    video.srcObject = null;
    audio.srcObject = null;
    speakButton.disabled = true;
    disconnectButton.disabled = true;
    connectButton.disabled = false;
    setStatus(message);

}

/** Запрашивает ответ LLM у Kotlin backend и передаёт его в D-ID. */
async function speak() {

    const value =
        text.value.trim();


    if (!value) {

        setStatus(
            "Введите текст"
        );

        return;

    }


    if (!agentManager && !simliClient) {

        setStatus(
            "Сначала подключите аватар"
        );

        return;

    }


    try {

        const speakStartedAt = performance.now();

        speakButton.disabled = true;


        setStatus("Запрашиваю ответ...");

        const config = await loadConfig();
        if (config.avatar_provider === "simli") {
            if (!simliConnected || !simliClient) {
                setStatus("Сессия Simli завершена. Сначала подключите аватара заново.");
                return;
            }
            interruptActiveTurn(config);
            await speakWithSimli(config, value, speakStartedAt);
            return;
        }
        const backendStartedAt = performance.now();
        const response = await fetch(`${config.chat_api_url}/api/chat`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                sessionId: chatSessionId,
                message: value,
                scenario: scenarioForNewSession()
            })
        });

        const payload = await response.json().catch(() => ({}));
        logTiming("chat_backend_response", backendStartedAt, { status: response.status });

        if (!response.ok) {
            throw new Error(payload.error || "Ошибка chat backend");
        }

        chatSessionId = payload.sessionId;
        setStatus("Аватар говорит...");


        const didSpeakStartedAt = performance.now();
        await agentManager.speak({

            type: "text",

            input: payload.assistantMessage

        });
        logTiming("did_speak_completed", didSpeakStartedAt, { sessionId: chatSessionId });
        logTiming("speak_total", speakStartedAt, { sessionId: chatSessionId });


        setStatus(
            "Готов"
        );


    }
    catch (error) {

        console.error(error);

        setStatus(
            "Ошибка: " +
            error.message
        );

    }
    finally {

        speakButton.disabled =
            false;

    }

}

/** Преобразует HTTP URL Kotlin API в URL browser WebSocket. */
function streamUrl(chatApiUrl) {

    const url = new URL("/api/chat/stream", chatApiUrl);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();

}

/** Передаёт Gemini text stream и PCM16 фреймы в уже подключённый Simli client. */
async function speakWithSimli(config, message, speakStartedAt) {

    setStatus("Запрашиваю потоковый ответ...");
    const turn = createLatencyTurn(speakStartedAt);
    activeLatencyTurn = turn;
    let receivedFirstPcm = false;
    let receivedFirstDelta = false;

    const relay = openSimliStream({
        url: streamUrl(config.chat_api_url),
        sessionId: chatSessionId,
        message,
        scenario: scenarioForNewSession(),
        simliClient,
        turnId: turn.turnId,
        onSession: (sessionId) => {
            chatSessionId = sessionId;
            turn.sessionId = sessionId;
            markTurnLatency(turn, "session_received");
        },
        onDelta: () => {
            if (!receivedFirstDelta) {
                receivedFirstDelta = true;
                markTurnLatency(turn, "gemini_first_delta");
                logTiming("gemini_first_delta", speakStartedAt);
            }
        },
        onFirstPcm: (bytes) => {
            if (!receivedFirstPcm) {
                receivedFirstPcm = true;
                markTurnLatency(turn, "browser_first_pcm");
                logTiming("browser_first_pcm", speakStartedAt, { bytes });
            }
        },
        onMetrics: (payload) => {
            turn.backendMetrics = payload.metrics;
            console.info("[latency]", { turnId: turn.turnId, backend: payload.metrics, outcome: payload.outcome });
        },
        onDone: (sessionId) => {
            chatSessionId = sessionId || chatSessionId;
            turn.sessionId = chatSessionId;
            markTurnLatency(turn, "stream_completed");
            logTiming("stream_completed", speakStartedAt, { sessionId: chatSessionId });
        }
    });
    streamRelay = relay;
    try {
        await relay.completion;
    } catch (error) {
        if (turn.interrupted) {
            return;
        }
        finishTurnLatency(turn, config, "stream_failed");
        throw error;
    } finally {
        if (streamRelay === relay) {
            streamRelay = null;
        }
    }

    logTiming("speak_total", speakStartedAt, { sessionId: chatSessionId, provider: "simli" });
    window.setTimeout(() => {
        if (activeLatencyTurn === turn && !avatarSpeaking) {
            finishTurnLatency(turn, config, "completed_without_avatar_signal");
            activeLatencyTurn = null;
        }
    }, 3_000);
}

/** Отключает текущий avatar transport и отменяет незавершённый поток речи. */
async function disconnect() {

    if (!agentManager && !simliClient) {

        return;

    }


    try {

        if (streamRelay) {
            streamRelay.cancel();
            streamRelay = null;
        }

        if (simliClient) {
            simliClient.ClearBuffer();
            await simliClient.stop();
            if (simliSessionStartedAt) {
                logTiming("simli_session_closed", simliSessionStartedAt, {
                    speakingTotalMs: Math.round(simliSpeakingTotalMs)
                });
            }
            simliClient = null;
            simliConnected = false;
            simliSessionStartedAt = null;
            simliSpeakingStartedAt = null;
            simliSpeakingTotalMs = 0;
            audio.srcObject = null;
        }

        if (agentManager) {
            await agentManager.disconnect();
        }

    }
    catch (error) {

        console.error(error);

    }


    agentManager = null;

    video.srcObject = null;


    speakButton.disabled =
        true;

    disconnectButton.disabled =
        true;

    connectButton.disabled =
        false;


    setStatus(
        "Отключено"
    );

}


connectButton.addEventListener(
    "click",
    connect
);


speakButton.addEventListener(
    "click",
    speak
);


disconnectButton.addEventListener(
    "click",
    disconnect
);

renderScenarioLabel();
