import { openSimliStream } from "./simli-stream-client.js";

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


let agentManager = null;
let simliClient = null;
let streamRelay = null;
let avatarSpeaking = false;
let simliSessionStartedAt = null;
let simliSpeakingStartedAt = null;
let simliSpeakingTotalMs = 0;
let chatSessionId = null;
let appConfig = null;
const pageStartedAt = performance.now();

/** Логирует измерение пользовательского пути без содержимого сообщений и секретов. */
function logTiming(event, startedAt, details = {}) {

    console.info("[timing]", {
        event,
        durationMs: Math.round(performance.now() - startedAt),
        sincePageStartMs: Math.round(performance.now() - pageStartedAt),
        ...details
    });

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
        agentManager =
            await window.DID.createAgentManager(

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
    const tokenStartedAt = performance.now();
    const response = await fetch(`${config.chat_api_url}/api/avatar/simli/session`, { method: "POST" });
    logTiming("simli_session_token_response", tokenStartedAt, { status: response.status });
    const payload = await response.json().catch(() => ({}));

    if (!response.ok) {
        throw new Error(payload.error || "Не удалось открыть сессию Simli");
    }

    simliClient = new window.Simli.SimliClient(
        payload.token,
        video,
        audio,
        null,
        window.Simli.LogLevel.INFO,
        payload.transport
    );
    simliClient.on("start", () => {
        simliSessionStartedAt = performance.now();
        logTiming("simli_start", connectStartedAt);
        setStatus("Аватар подключён");
    });
    simliClient.on("speaking", () => {
        avatarSpeaking = true;
        simliSpeakingStartedAt = performance.now();
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
        if (!streamRelay) {
            setStatus("Готов");
            speakButton.disabled = false;
        }
    });
    simliClient.on("ack", () => console.info("[timing]", { event: "simli_ack" }));
    simliClient.on("startup_error", (error) => console.error("Simli startup error", error));
    simliClient.on("error", (error) => {
        console.error("Simli error", error);
        setStatus("Ошибка Simli");
    });

    const startedAt = performance.now();
    await simliClient.start();
    logTiming("simli_connect_completed", startedAt);
    logTiming("avatar_connect_total", connectStartedAt, { provider: "simli" });
    speakButton.disabled = false;
    disconnectButton.disabled = false;
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
            await speakWithSimli(config, value, speakStartedAt);
            return;
        }
        const backendStartedAt = performance.now();
        const response = await fetch(`${config.chat_api_url}/api/chat`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                sessionId: chatSessionId,
                message: value
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

    if (avatarSpeaking) {
        throw new Error("Аватар ещё озвучивает предыдущий ответ");
    }

    setStatus("Запрашиваю потоковый ответ...");
    let receivedFirstPcm = false;
    let receivedFirstDelta = false;

    streamRelay = openSimliStream({
        url: streamUrl(config.chat_api_url),
        sessionId: chatSessionId,
        message,
        simliClient,
        onSession: (sessionId) => { chatSessionId = sessionId; },
        onDelta: () => {
            if (!receivedFirstDelta) {
                receivedFirstDelta = true;
                logTiming("gemini_first_delta", speakStartedAt);
            }
        },
        onFirstPcm: (bytes) => {
            if (!receivedFirstPcm) {
                receivedFirstPcm = true;
                logTiming("browser_first_pcm", speakStartedAt, { bytes });
            }
        },
        onDone: (sessionId) => {
            chatSessionId = sessionId || chatSessionId;
            logTiming("stream_completed", speakStartedAt, { sessionId: chatSessionId });
        }
    });
    try {
        await streamRelay.completion;
    } finally {
        streamRelay = null;
    }

    logTiming("speak_total", speakStartedAt, { sessionId: chatSessionId, provider: "simli" });
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
