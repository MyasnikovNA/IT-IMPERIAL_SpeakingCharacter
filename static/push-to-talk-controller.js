import { PushToTalkTranscriber, VoiceState } from "./scribe-client.js?v=13";

const labels = Object.freeze({
    [VoiceState.DISCONNECTED]: "Микрофон недоступен",
    [VoiceState.CONNECTING]: "Подключаю микрофон...",
    [VoiceState.READY]: "🎙 Удерживайте, чтобы говорить",
    [VoiceState.LISTENING]: "🔴 Говорите...",
    [VoiceState.COMMITTING]: "Распознаю...",
    [VoiceState.ERROR]: "Распознавание речи недоступно"
});

/** Связывает PTT state machine с DOM и общим submit callback без знания LLM-пайплайна. */
export function createPushToTalkController({
    button,
    transcriptElement,
    ensureAvatarConnected,
    interrupt,
    submit,
    setStatus,
    onStateChange = () => {},
    transcriberFactory = (options) => new PushToTalkTranscriber(options)
}) {
    let avatarConnected = false;
    let keyboardPressed = false;
    let activePointerId = null;
    const render = (state) => {
        button.textContent = labels[state];
        button.disabled = !avatarConnected || state === VoiceState.CONNECTING || state === VoiceState.COMMITTING;
        button.classList.toggle("is-listening", state === VoiceState.LISTENING);
        button.classList.toggle("is-committing", state === VoiceState.COMMITTING);
        button.setAttribute("aria-pressed", String(state === VoiceState.LISTENING));
        onStateChange(state);
    };
    const transcriber = transcriberFactory({
        onPartial: (value) => { transcriptElement.textContent = value; },
        onCommitted: async (value, meta) => {
            transcriptElement.textContent = value;
            if (meta.maxDuration) setStatus("Реплика достигла максимальной длины и отправлена.");
            await submit(value, { source: "voice" });
        },
        onStateChange: (state) => render(state),
        onError: (message) => setStatus(message),
        onTelemetry: (event, details) => console.info("[voice]", { event, ...details })
    });
    const start = async () => {
        if (!avatarConnected) return;
        if (!ensureAvatarConnected()) return;
        try {
            await transcriber.ensureConnected();
        } catch (_) {
            return false;
        }
        interrupt();
        transcriptElement.textContent = "";
        try { return await transcriber.startListening(); } catch (_) { return false; /* facade updates UI and fallback stays available */ }
    };
    const stop = () => transcriber.stopListeningAndCommit();
    button.addEventListener("pointerdown", async (event) => {
        event.preventDefault();
        if (activePointerId !== null) return;
        activePointerId = event.pointerId;
        button.setPointerCapture?.(event.pointerId);
        await start();
        if (activePointerId !== event.pointerId) stop();
    });
    for (const eventName of ["pointerup", "pointercancel", "lostpointercapture"]) {
        button.addEventListener(eventName, (event) => {
            event.preventDefault();
            if (activePointerId !== event.pointerId) return;
            activePointerId = null;
            stop();
        });
    }
    window.addEventListener("keydown", async (event) => {
        if ((event.code !== "Space" && event.code !== "Enter") || event.repeat || event.target?.matches("textarea, input, select")) return;
        event.preventDefault();
        keyboardPressed = true;
        await start();
        if (!keyboardPressed) stop();
    });
    window.addEventListener("keyup", (event) => {
        if (!keyboardPressed || (event.code !== "Space" && event.code !== "Enter")) return;
        event.preventDefault();
        keyboardPressed = false;
        stop();
    });
    return {
        configure(options) {
            transcriber.configure(options);
        },
        /** Запрашивает permission до длительных сетевых операций подключения аватара. */
        async requestMicrophonePermission() {
            return transcriber.requestMicrophonePermission();
        },
        /** Подготавливает Safari Web Audio без отдельного getUserMedia probe. */
        prepareBrowserAudio() {
            transcriber.prepareBrowserAudio();
        },
        async connect() {
            avatarConnected = true;
            render(VoiceState.CONNECTING);
            try { await transcriber.connect(); } catch (_) { /* fallback remains available */ }
        },
        disconnect() {
            avatarConnected = false;
            transcriber.close();
            render(VoiceState.DISCONNECTED);
            transcriptElement.textContent = "";
        },
        get state() { return transcriber.state; },
        transcriber
    };
}
