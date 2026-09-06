/** Открывает browser WebSocket и передаёт полученные PCM16 фреймы в Simli SDK. */
export function openSimliStream({
    url,
    sessionId,
    message,
    scenario,
    simliClient,
    onSession,
    onDelta,
    onFirstPcm,
    onDone,
    onMetrics,
    turnId,
    WebSocketImpl = WebSocket
}) {

    let settled = false;
    let resolveCompletion;
    let rejectCompletion;
    const completion = new Promise((resolve, reject) => {
        resolveCompletion = resolve;
        rejectCompletion = reject;
    });
    const socket = new WebSocketImpl(url);
    socket.binaryType = "arraybuffer";

    const fail = (error) => {
        if (!settled) {
            settled = true;
            rejectCompletion(error);
        }
    };

    socket.onopen = () => {
        socket.send(JSON.stringify({ type: "start", sessionId, message, scenario, turnId }));
    };
    socket.onmessage = async (event) => {
        if (typeof event.data === "string") {
            const payload = JSON.parse(event.data);
            if (payload.type === "session") {
                onSession(payload.sessionId);
            } else if (payload.type === "delta") {
                onDelta();
            } else if (payload.type === "metrics") {
                onMetrics(payload);
            } else if (payload.type === "error") {
                fail(new Error(payload.error || "Ошибка потокового ответа"));
            } else if (payload.type === "done" && !settled) {
                settled = true;
                onDone(payload.sessionId);
                resolveCompletion();
            }
            return;
        }

        const buffer = event.data instanceof ArrayBuffer
            ? event.data
            : await event.data.arrayBuffer();
        const pcm = new Uint8Array(buffer);
        onFirstPcm(pcm.byteLength);
        simliClient.sendAudioData(pcm);
    };
    socket.onerror = () => fail(new Error("WebSocket потокового чата недоступен"));
    socket.onclose = () => {
        if (!settled) {
            fail(new Error("Потоковый чат был закрыт до завершения"));
        }
    };

    return {
        completion,
        socket,
        /** Отменяет backend coroutine и очищает уже поставленное в очередь аудио Simli. */
        cancel() {
            if (!settled) {
                socket.send(JSON.stringify({ type: "cancel" }));
                simliClient.ClearBuffer();
                socket.close();
                fail(new Error("Потоковый ответ отменён"));
            }
        }
    };
}
