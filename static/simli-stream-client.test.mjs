import assert from "node:assert/strict";
import test from "node:test";
import { openSimliStream, PcmChunkBuffer } from "./simli-stream-client.js";

test("PCM rechunker передаёт Simli блоки фиксированного размера и хвост", () => {
    const buffer = new PcmChunkBuffer(6);

    assert.deepEqual(buffer.push(new Uint8Array([1, 2, 3, 4])), []);
    assert.deepEqual(buffer.push(new Uint8Array([5, 6, 7, 8, 9])).map((chunk) => [...chunk]), [[1, 2, 3, 4, 5, 6]]);
    assert.deepEqual(buffer.flush().map((chunk) => [...chunk]), [[7, 8, 9]]);
});

class FakeSocket {
    static instance;

    constructor() {
        FakeSocket.instance = this;
        this.sent = [];
        this.closed = false;
    }

    send(value) { this.sent.push(value); }

    close() { this.closed = true; }

    emitOpen() { this.onopen(); }

    async emitText(value) { await this.onmessage({ data: JSON.stringify(value) }); }

    async emitPcm(bytes) { await this.onmessage({ data: bytes.buffer }); }
}

test("relay передаёт PCM в Simli в порядке поступления", async () => {
    const audio = [];
    let backendMetrics = null;
    const client = { sendAudioData: (pcm) => audio.push([...pcm]), ClearBuffer: () => {} };
    let sessionId = null;
    const relay = openSimliStream({
        url: "ws://test/api/chat/stream",
        sessionId: null,
        message: "Текст",
        pcmChunkBytes: 2,
        simliClient: client,
        onSession: (value) => { sessionId = value; },
        onDelta: () => {},
        onFirstPcm: () => {},
        onDone: () => {},
        onMetrics: (payload) => { backendMetrics = payload.metrics; },
        WebSocketImpl: FakeSocket
    });
    const socket = FakeSocket.instance;

    socket.emitOpen();
    await socket.emitText({ type: "session", sessionId: "session-1" });
    await socket.emitPcm(new Uint8Array([1, 2]));
    await socket.emitPcm(new Uint8Array([3, 4]));
    await socket.emitText({ type: "metrics", metrics: { gemini_first_delta: 120 } });
    await socket.emitText({ type: "done", sessionId: "session-1" });
    await relay.completion;

    assert.equal(sessionId, "session-1");
    assert.deepEqual(audio, [[1, 2], [3, 4]]);
    assert.deepEqual(backendMetrics, { gemini_first_delta: 120 });
    assert.deepEqual(JSON.parse(socket.sent[0]), { type: "start", sessionId: null, message: "Текст" });
});

test("отмена очищает Simli buffer и уведомляет backend", () => {
    let cleared = false;
    const client = { sendAudioData: () => {}, ClearBuffer: () => { cleared = true; } };
    const relay = openSimliStream({
        url: "ws://test/api/chat/stream",
        sessionId: "session-1",
        message: "Текст",
        simliClient: client,
        onSession: () => {},
        onDelta: () => {},
        onFirstPcm: () => {},
        onDone: () => {},
        onMetrics: () => {},
        WebSocketImpl: FakeSocket
    });
    const socket = FakeSocket.instance;

    socket.emitOpen();
    relay.cancel();

    assert.equal(cleared, true);
    assert.equal(socket.closed, true);
    assert.deepEqual(JSON.parse(socket.sent[1]), { type: "cancel" });
    relay.completion.catch(() => {});
});

test("relay передаёт сценарий только в start-команде новой сессии", () => {
    const relay = openSimliStream({
        url: "ws://test/api/chat/stream",
        sessionId: null,
        message: "Начнём тренировку",
        scenario: { presetId: "sales-discovery" },
        simliClient: { sendAudioData: () => {}, ClearBuffer: () => {} },
        onSession: () => {},
        onDelta: () => {},
        onFirstPcm: () => {},
        onDone: () => {},
        onMetrics: () => {},
        WebSocketImpl: FakeSocket
    });
    const socket = FakeSocket.instance;

    socket.emitOpen();

    assert.deepEqual(JSON.parse(socket.sent[0]), {
        type: "start",
        sessionId: null,
        message: "Начнём тренировку",
        scenario: { presetId: "sales-discovery" }
    });
    relay.cancel();
    relay.completion.catch(() => {});
});
