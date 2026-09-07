/** Состояния управляемого пользователем Scribe push-to-talk соединения. */
export const VoiceState = Object.freeze({
    DISCONNECTED: "DISCONNECTED",
    CONNECTING: "CONNECTING",
    READY: "READY",
    LISTENING: "LISTENING",
    COMMITTING: "COMMITTING",
    ERROR: "ERROR"
});

const MIN_PTT_DURATION_MS = 120;
const MAX_PTT_DURATION_MS = 25_000;
const COMMIT_TIMEOUT_MS = 4_000;
const CONNECT_TIMEOUT_MS = 10_000;
const MICROPHONE_TRACK_POLL_MS = 25;
const RECONNECT_DELAYS_MS = [250, 750, 1_500];

/** Разблокирует Web Audio в исходном browser gesture; особенно важно для Safari. */
function unlockBrowserAudio() {
    const AudioContextConstructor = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AudioContextConstructor) return null;
    try {
        const context = new AudioContextConstructor();
        // Вызов resume происходит синхронно в обработчике click, до await сети.
        void context.resume().catch(() => {});
        return context;
    } catch (_) {
        return null;
    }
}

/** Возвращает безопасное человекочитаемое объяснение browser microphone ошибки. */
export function microphoneErrorMessage(error) {
    if (!globalThis.isSecureContext && typeof window !== "undefined") {
        return "Микрофон доступен только через HTTPS или localhost.";
    }
    switch (error?.name) {
        case "NotAllowedError": return "Нет доступа к микрофону. Разрешите его в настройках браузера.";
        case "NotFoundError": return "Микрофон не найден.";
        case "NotReadableError": return "Микрофон занят другим приложением.";
        case "OverconstrainedError": return "Выбранный микрофон недоступен.";
        case "auth_error": return "ElevenLabs отклонил подключение распознавания. Проверьте API-ключ и доступ к Scribe.";
        case "quota_exceeded":
        case "rate_limited":
        case "resource_exhausted": return "Лимит ElevenLabs для распознавания речи исчерпан. Текстовый ввод остаётся доступен.";
        case "ScribeConnectionTimeout": return "Распознавание речи не подключилось вовремя. Проверьте сеть и подключите аватара ещё раз.";
        case "ScribeBackendUnavailable": return "Backend не смог получить токен распознавания речи. Проверьте соединение с приложением.";
        default:
            if (/permission|notallowed|denied/i.test(String(error?.message || ""))) {
                return "Нет доступа к микрофону. Разрешите его в настройках браузера.";
            }
            if (/audio.?worklet|audio.?context/i.test(String(error?.message || ""))) {
                return "Браузер не смог подготовить аудио для распознавания. Перезагрузите страницу и подключите аватара ещё раз.";
            }
            return "Не удалось подключить распознавание речи. Текстовый ввод остаётся доступен.";
    }
}

/** Управляет token, Scribe и manual commit, не зная ничего о чате или аватаре. */
export class PushToTalkTranscriber {
    constructor({
        tokenUrl = "/api/stt/token",
        modelId = "scribe_v2_realtime",
        languageCode = "ru",
        onPartial = () => {},
        onCommitted = () => {},
        onStateChange = () => {},
        onError = () => {},
        onTelemetry = () => {},
        sdkLoader = () => import("/vendor/elevenlabs-scribe.js"),
        // Safari проверяет receiver встроенного Window.fetch. Стрелка сохраняет
        // правильный window-контекст, когда fetch вызывается через this.fetchImpl.
        fetchImpl = (...args) => globalThis.fetch(...args),
        now = () => performance.now(),
        // Как и fetch, Safari проверяет receiver нативных Window-таймеров.
        // Обёртки сохраняют window-контекст при вызове через поле экземпляра.
        setTimeoutImpl = (...args) => globalThis.setTimeout(...args),
        clearTimeoutImpl = (...args) => globalThis.clearTimeout(...args),
        microphoneAvailable = () => typeof navigator === "undefined" || Boolean(navigator.mediaDevices?.getUserMedia),
        audioUnlocker = unlockBrowserAudio,
        microphonePermissionRequester = async () => {
            if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
                throw new Error("Microphone API is unavailable");
            }
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
            stream.getTracks().forEach((track) => track.stop());
        }
    } = {}) {
        this.tokenUrl = tokenUrl;
        this.modelId = modelId;
        this.languageCode = languageCode || undefined;
        this.onPartial = onPartial;
        this.onCommitted = onCommitted;
        this.onStateChange = onStateChange;
        this.onError = onError;
        this.onTelemetry = onTelemetry;
        this.sdkLoader = sdkLoader;
        this.fetchImpl = fetchImpl;
        this.now = now;
        this.setTimeoutImpl = setTimeoutImpl;
        this.clearTimeoutImpl = clearTimeoutImpl;
        this.microphoneAvailable = microphoneAvailable;
        this.audioUnlocker = audioUnlocker;
        this.audioUnlockContext = null;
        this.microphonePermissionRequester = microphonePermissionRequester;
        this.state = VoiceState.DISCONNECTED;
        this.connection = null;
        this.connectionEpoch = 0;
        this.connectPromise = null;
        this.keepConnected = false;
        this.pendingManualCommit = null;
        this.pttTurnId = 0;
        this.pressStartedAt = null;
        this.commitTimer = null;
        this.maxTurnTimer = null;
        this.reconnectTimer = null;
        this.reconnectAttempt = 0;
        this.firstPartialTurnId = null;
    }

    /** Обновляет безопасные runtime-параметры до открытия browser connection. */
    configure({ tokenUrl, modelId, languageCode } = {}) {
        if (this.state !== VoiceState.DISCONNECTED) {
            throw new Error("Scribe connection must be disconnected before reconfiguration");
        }
        if (tokenUrl) this.tokenUrl = tokenUrl;
        if (modelId) this.modelId = modelId;
        this.languageCode = languageCode || undefined;
    }

    /** Запрашивает browser permission в рамках исходного user gesture и сразу освобождает probe stream. */
    async requestMicrophonePermission() {
        if (!this.microphoneAvailable()) {
            const error = new Error("Microphone API is unavailable");
            this.#fail(error);
            return false;
        }
        try {
            // Scribe создаёт AudioContext после асинхронного token fetch. На Safari
            // это уже может быть вне user gesture, поэтому заранее удерживаем
            // разблокированный контекст до готовности Scribe.
            this.audioUnlockContext ??= this.audioUnlocker();
            await this.microphonePermissionRequester();
            return true;
        } catch (error) {
            this.#fail(error);
            return false;
        }
    }

    /** Разблокирует Web Audio до асинхронного token fetch, не захватывая микрофон повторно. */
    prepareBrowserAudio() {
        this.audioUnlockContext ??= this.audioUnlocker();
    }

    /** Создаёт Scribe connection либо возвращает уже готовое. */
    async connect() {
        this.keepConnected = true;
        return this.ensureConnected();
    }

    /** Восстанавливает соединение с новым single-use token после любого закрытия. */
    async ensureConnected() {
        if (this.state === VoiceState.READY && this.connection) return this.connection;
        if (this.connectPromise) return this.connectPromise;
        if (this.connection) {
            try { this.connection.close(); } catch (_) { /* stale connection cleanup is best effort */ }
            this.connection = null;
        }
        if (!this.microphoneAvailable()) {
            this.#fail(new Error("Микрофон доступен только через HTTPS или localhost."));
            throw new Error("Microphone API is unavailable");
        }
        const epoch = ++this.connectionEpoch;
        this.#setState(VoiceState.CONNECTING);
        this.#telemetry("stt_connect_started");
        this.connectPromise = this.#open(epoch);
        try {
            return await this.connectPromise;
        } finally {
            if (epoch === this.connectionEpoch) this.connectPromise = null;
        }
    }

    async #open(epoch) {
        let stage = "token";
        try {
            // Cache-Control: no-store приходит от Kotlin endpoint. Не передаём
            // FetchRequest.cache: Safari в этом cross-origin сценарии может
            // завершать запрос TypeError до отправки token request.
            const tokenResponse = await this.fetchImpl(this.tokenUrl, { method: "POST" });
            if (!tokenResponse.ok) {
                const error = new Error((await tokenResponse.json().catch(() => ({}))).error || "Speech recognition is unavailable");
                error.name = "ScribeBackendUnavailable";
                throw error;
            }
            const { token } = await tokenResponse.json();
            if (typeof token !== "string" || token.trim() === "") throw new Error("Speech recognition returned an invalid token");
            stage = "sdk_load";
            const sdk = await this.sdkLoader();
            if (epoch !== this.connectionEpoch) throw new Error("Stale Scribe connection");
            stage = "scribe_connect";
            const connection = sdk.Scribe.connect({
                token,
                modelId: this.modelId,
                commitStrategy: sdk.CommitStrategy.MANUAL,
                ...(this.languageCode ? { languageCode: this.languageCode } : {}),
                microphone: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
            this.connection = connection;
            stage = "microphone_ready";
            await this.#waitForReady(connection, sdk.RealtimeEvents, epoch);
            if (epoch !== this.connectionEpoch || this.connection !== connection) throw new Error("Stale Scribe connection");
            this.reconnectAttempt = 0;
            this.#releaseAudioUnlockContext();
            this.#setState(VoiceState.READY);
            this.#telemetry("stt_connected");
            return connection;
        } catch (error) {
            if (epoch === this.connectionEpoch) this.#fail(error, { stage });
            throw error;
        }
    }

    #waitForReady(connection, events, epoch) {
        return new Promise((resolve, reject) => {
            let settled = false;
            let sessionStarted = false;
            let microphonePoll = null;
            const cleanup = () => {
                this.clearTimeoutImpl(timeout);
                if (microphonePoll) this.clearTimeoutImpl(microphonePoll);
                microphonePoll = null;
            };
            const resolveOnce = () => {
                if (settled) return;
                settled = true;
                cleanup();
                resolve();
            };
            const rejectOnce = (error) => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(error);
            };
            const timeout = this.setTimeoutImpl(
                () => {
                    const error = new Error("Scribe connection timed out while preparing microphone");
                    error.name = "ScribeConnectionTimeout";
                    rejectOnce(error);
                },
                CONNECT_TIMEOUT_MS
            );
            const isCurrent = () => epoch === this.connectionEpoch && this.connection === connection;
            const waitForMicrophoneTrack = () => {
                if (!isCurrent() || !sessionStarted || settled) return;
                try {
                    // SDK emits session_started when its WebSocket is ready, but creates
                    // MediaStreamTrack asynchronously afterwards. mute() is the public
                    // SDK operation that confirms the track is ready for PTT.
                    connection.mute();
                    resolveOnce();
                } catch (_) {
                    microphonePoll = this.setTimeoutImpl(waitForMicrophoneTrack, MICROPHONE_TRACK_POLL_MS);
                }
            };
            connection.on(events.SESSION_STARTED, () => {
                if (!isCurrent()) return;
                sessionStarted = true;
                waitForMicrophoneTrack();
            });
            connection.on(events.PARTIAL_TRANSCRIPT, (data) => {
                if (!isCurrent() || this.state !== VoiceState.LISTENING) return;
                const text = String(data?.text || "");
                if (text) {
                    if (this.firstPartialTurnId !== this.pttTurnId) {
                        this.firstPartialTurnId = this.pttTurnId;
                        this.#telemetry("stt_first_partial");
                    }
                    this.onPartial(text);
                }
            });
            connection.on(events.COMMITTED_TRANSCRIPT, (data) => this.#handleCommitted(epoch, connection, data));
            connection.on(events.ERROR, (data) => {
                if (!isCurrent()) return;
                // Scribe SDK normalizes local and provider failures as
                // { message_type, error }, not { message }. Keep the code for
                // a safe UI category and never expose token or transcript data.
                const error = new Error(data?.error || data?.message || "Speech recognition error");
                error.name = String(data?.message_type || data?.code || "ScribeError");
                this.#fail(error);
                this.connection = null;
                try { connection.close(); } catch (_) { /* provider already closed or failed */ }
                this.#scheduleReconnect();
                rejectOnce(error);
            });
            connection.on(events.CLOSE, () => {
                if (!isCurrent()) return;
                this.connection = null;
                this.#clearTurnTimers();
                if (this.keepConnected) {
                    this.#setState(VoiceState.DISCONNECTED);
                    this.#scheduleReconnect();
                } else {
                    this.#setState(VoiceState.DISCONNECTED);
                }
                rejectOnce(new Error("Scribe connection closed"));
            });
        });
    }

    /** Включает захват речи ровно для одного PTT turn. */
    async startListening() {
        if (this.state === VoiceState.LISTENING || this.state === VoiceState.COMMITTING) return false;
        const connection = await this.ensureConnected();
        if (this.state !== VoiceState.READY || connection !== this.connection) return false;
        this.pendingManualCommit = null;
        this.pressStartedAt = this.now();
        this.pttTurnId += 1;
        this.firstPartialTurnId = null;
        connection.unmute();
        this.#setState(VoiceState.LISTENING);
        this.#telemetry("ptt_pressed");
        this.maxTurnTimer = this.setTimeoutImpl(() => this.stopListeningAndCommit({ maxDuration: true }), MAX_PTT_DURATION_MS);
        return true;
    }

    /** Останавливает захват и вручную коммитит последний сегмент Scribe. */
    stopListeningAndCommit({ maxDuration = false } = {}) {
        if (this.state !== VoiceState.LISTENING || !this.connection) return false;
        const elapsed = this.now() - this.pressStartedAt;
        this.#clearTurnTimers();
        this.connection.mute();
        if (elapsed < MIN_PTT_DURATION_MS && !maxDuration) {
            try {
                // Scribe хранит manual segment до commit; очищаем случайный tap,
                // но pendingManualCommit остаётся null, поэтому он не создаст USER turn.
                this.connection.commit();
            } catch (_) { /* connection may have closed during the short tap */ }
            this.#setState(VoiceState.READY);
            return false;
        }
        const turnId = this.pttTurnId;
        this.pendingManualCommit = { turnId, releasedAt: this.now(), maxDuration };
        this.#setState(VoiceState.COMMITTING);
        this.#telemetry("ptt_released", { durationMs: Math.round(elapsed) });
        try {
            this.connection.commit();
            this.#telemetry("stt_commit_requested");
        } catch (error) {
            this.#fail(error);
            return false;
        }
        this.commitTimer = this.setTimeoutImpl(() => {
            if (this.pendingManualCommit?.turnId !== turnId) return;
            this.pendingManualCommit = null;
            this.#fail(new Error("Не удалось получить распознанную речь. Попробуйте ещё раз."));
            this.#scheduleReconnect();
        }, COMMIT_TIMEOUT_MS);
        return true;
    }

    /** Освобождает WebSocket, микрофон и все незавершённые PTT таймеры. */
    close() {
        this.keepConnected = false;
        this.connectionEpoch += 1;
        this.#clearTurnTimers();
        if (this.reconnectTimer) this.clearTimeoutImpl(this.reconnectTimer);
        this.reconnectTimer = null;
        const connection = this.connection;
        this.connection = null;
        this.connectPromise = null;
        this.pendingManualCommit = null;
        this.#releaseAudioUnlockContext();
        try { connection?.mute(); } catch (_) { /* connection may already be closed */ }
        try { connection?.close(); } catch (_) { /* best effort cleanup */ }
        this.#setState(VoiceState.DISCONNECTED);
    }

    #handleCommitted(epoch, connection, data) {
        if (epoch !== this.connectionEpoch || connection !== this.connection) return;
        const pending = this.pendingManualCommit;
        if (!pending) return;
        this.pendingManualCommit = null;
        this.#clearTurnTimers();
        const transcript = String(data?.text || "").trim();
        if (!transcript) {
            this.#setState(VoiceState.READY);
            this.onError("Речь не распознана, попробуйте ещё раз.");
            return;
        }
        this.#telemetry("stt_committed", {
            chars: transcript.length,
            wordCount: transcript.split(/\s+/).length,
            releaseToCommittedMs: Math.round(this.now() - pending.releasedAt),
            voiceTurnDurationMs: Math.round(this.now() - this.pressStartedAt)
        });
        this.#setState(VoiceState.READY);
        Promise.resolve(this.onCommitted(transcript, {
            maxDuration: pending.maxDuration,
            turnId: pending.turnId,
            releasedAt: pending.releasedAt
        }))
            .catch((error) => this.#fail(error));
    }

    #scheduleReconnect() {
        if (!this.keepConnected || this.reconnectTimer || this.reconnectAttempt >= RECONNECT_DELAYS_MS.length) return;
        const delay = RECONNECT_DELAYS_MS[this.reconnectAttempt++];
        this.reconnectTimer = this.setTimeoutImpl(async () => {
            this.reconnectTimer = null;
            try {
                await this.ensureConnected();
                this.#telemetry("stt_reconnected");
            } catch (_) {
                this.#scheduleReconnect();
            }
        }, delay);
    }

    #clearTurnTimers() {
        if (this.commitTimer) this.clearTimeoutImpl(this.commitTimer);
        if (this.maxTurnTimer) this.clearTimeoutImpl(this.maxTurnTimer);
        this.commitTimer = null;
        this.maxTurnTimer = null;
    }

    #releaseAudioUnlockContext() {
        const context = this.audioUnlockContext;
        this.audioUnlockContext = null;
        try { void context?.close?.(); } catch (_) { /* browser-specific unlock is best effort */ }
    }

    #setState(state) {
        if (this.state === state) return;
        this.state = state;
        this.onStateChange(state);
    }

    #telemetry(event, details = {}) {
        this.onTelemetry(event, details);
    }

    #fail(error, details = {}) {
        this.#clearTurnTimers();
        this.pendingManualCommit = null;
        this.#setState(VoiceState.ERROR);
        // Для диагностики Safari сохраняем только тип, шаг и короткое сообщение.
        // Token, transcript и provider payload намеренно никогда не попадают в console.
        this.#telemetry("stt_error", {
            name: error?.name || "Error",
            stage: details.stage || "runtime",
            message: String(error?.message || "").slice(0, 160)
        });
        this.onError(microphoneErrorMessage(error));
    }
}
