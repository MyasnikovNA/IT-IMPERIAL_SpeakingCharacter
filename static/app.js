const video =
    document.getElementById("avatar");

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

/** Загружает и кэширует учётные данные D-ID и URL Kotlin Chat API. */
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

/** Подключает существующий менеджер D-ID и включает элементы управления диалогом. */
async function connect() {

    try {

        const connectStartedAt = performance.now();

        setStatus(
            "Подключение к D-ID..."
        );

        connectButton.disabled = true;


        const config =
            await loadConfig();


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


    if (!agentManager) {

        setStatus(
            "Сначала подключите аватар"
        );

        return;

    }


    try {

        const speakStartedAt = performance.now();

        speakButton.disabled =
            true;


        setStatus("Запрашиваю ответ...");

        const config = await loadConfig();
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

/** Отключает D-ID, сохраняя сессию чата в памяти до перезагрузки страницы. */
async function disconnect() {

    if (!agentManager) {

        return;

    }


    try {

        await agentManager.disconnect();

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
