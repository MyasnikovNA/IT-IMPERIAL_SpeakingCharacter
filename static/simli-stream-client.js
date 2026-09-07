/** Собирает произвольные PCM16 фреймы провайдера в стабильные блоки для Simli. */
export class PcmChunkBuffer {
    constructor(chunkBytes) {
        if (!Number.isInteger(chunkBytes) || chunkBytes <= 0 || chunkBytes % 2 !== 0) {
            throw new Error("PCM chunk size должен быть положительным чётным числом");
        }
        this.chunkBytes = chunkBytes;
        this.pending = new Uint8Array(0);
    }

    push(pcm) {
        const merged = new Uint8Array(this.pending.byteLength + pcm.byteLength);
        merged.set(this.pending);
        merged.set(pcm, this.pending.byteLength);
        const chunks = [];
        let offset = 0;
        while (merged.byteLength - offset >= this.chunkBytes) {
            chunks.push(merged.slice(offset, offset + this.chunkBytes));
            offset += this.chunkBytes;
        }
        this.pending = merged.slice(offset);
        return chunks;
    }

    flush() {
        const tail = this.pending;
        this.pending = new Uint8Array(0);
        return tail.byteLength ? [tail] : [];
    }
}

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
    onStale = () => {},
    isActive = () => true,
    turnId,
    pcmChunkBytes = 3000,
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
    const pcmBuffer = pcmChunkBytes === null ? null : new PcmChunkBuffer(pcmChunkBytes);
    socket.binaryType = "arraybuffer";

    const fail = (error) => {
        if (!settled) {
            settled = true;
            rejectCompletion(error);
        }
    };

    socket.onopen = () => {
        if (!isActive()) return;
        socket.send(JSON.stringify({ type: "start", sessionId, message, scenario, turnId }));
    };
    socket.onmessage = async (event) => {
        if (!isActive()) {
            if (typeof event.data === "string") {
                const type = (() => {
                    try { return JSON.parse(event.data).type; } catch (_) { return "text"; }
                })();
                if (type === "delta") onStale("text");
                if (type === "done") onStale("done");
            } else {
                onStale("pcm");
            }
            return;
        }
        if (typeof event.data === "string") {
            const payload = JSON.parse(event.data);
            if (payload.type === "session") {
                if (isActive()) onSession(payload.sessionId);
            } else if (payload.type === "delta") {
                if (isActive()) onDelta(payload.delta || "");
            } else if (payload.type === "metrics") {
                if (isActive()) onMetrics(payload);
            } else if (payload.type === "error") {
                fail(new Error(payload.error || "Ошибка потокового ответа"));
            } else if (payload.type === "done" && !settled) {
                if (!isActive()) return;
                settled = true;
                pcmBuffer?.flush().forEach((pcm) => simliClient.sendAudioData(pcm));
                onDone(payload.sessionId);
                resolveCompletion();
            }
            return;
        }

        const buffer = event.data instanceof ArrayBuffer
            ? event.data
            : await event.data.arrayBuffer();
        if (!isActive()) {
            onStale("pcm");
            return;
        }
        const pcm = new Uint8Array(buffer);
        onFirstPcm(pcm.byteLength);
        if (pcmBuffer) {
            pcmBuffer.push(pcm).forEach((chunk) => simliClient.sendAudioData(chunk));
        } else {
            simliClient.sendAudioData(pcm);
        }
    };
    socket.onerror = () => fail(new Error("WebSocket потокового чата недоступен"));
    socket.onclose = () => {
        if (!isActive()) return;
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
