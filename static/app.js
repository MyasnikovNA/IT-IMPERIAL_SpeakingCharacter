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


function setStatus(message) {

    status.textContent = message;

}


async function loadConfig() {

    const response =
        await fetch("/api/config");

    if (!response.ok) {

        throw new Error(
            "Не удалось получить конфигурацию"
        );

    }

    return await response.json();

}


async function connect() {

    try {

        setStatus(
            "Подключение к D-ID..."
        );

        connectButton.disabled = true;


        const config =
            await loadConfig();


        const callbacks = {

            onSrcObjectReady(stream) {

                console.log(
                    "WebRTC stream ready"
                );

                video.srcObject =
                    stream;

                video.play()
                    .catch(console.error);

            },


            onConnectionStateChange(state) {

                console.log(
                    "Connection:",
                    state
                );

                setStatus(
                    "Соединение: " + state
                );

            },


            onNewMessage(messages, type) {

                console.log(
                    "Message:",
                    messages,
                    type
                );

            },


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


        await agentManager.connect();


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

        speakButton.disabled =
            true;


        setStatus(
            "Аватар говорит..."
        );


        await agentManager.speak({

            type: "text",

            input: value

        });


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