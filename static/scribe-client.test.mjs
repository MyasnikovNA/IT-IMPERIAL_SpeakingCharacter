import test from "node:test";
import assert from "node:assert/strict";
import { microphoneErrorMessage, PushToTalkTranscriber, VoiceState } from "./scribe-client.js";

const events = { SESSION_STARTED: "session_started", PARTIAL_TRANSCRIPT: "partial_transcript", COMMITTED_TRANSCRIPT: "committed_transcript", ERROR: "error", CLOSE: "close" };

class FakeConnection {
    constructor() { this.handlers = new Map(); this.muteCalls = 0; this.unmuteCalls = 0; this.commitCalls = 0; this.closeCalls = 0; }
    on(name, callback) { this.handlers.set(name, callback); }
    emit(name, payload) { this.handlers.get(name)?.(payload); }
    mute() { this.muteCalls += 1; }
    unmute() { this.unmuteCalls += 1; }
    commit() { this.commitCalls += 1; }
    close() { this.closeCalls += 1; }
}

function createHarness() {
    const connections = [];
    const committed = [];
    const partial = [];
    let now = 1_000;
    let tokenRequests = 0;
    const timers = [];
    const transcriber = new PushToTalkTranscriber({
        fetchImpl: async () => ({ ok: true, json: async () => ({ token: `sutkn_${++tokenRequests}` }) }),
        sdkLoader: async () => ({
            Scribe: { connect: () => { const connection = new FakeConnection(); connections.push(connection); return connection; } },
            RealtimeEvents: events,
            CommitStrategy: { MANUAL: "manual" }
        }),
        onPartial: (value) => partial.push(value),
        onCommitted: (value) => committed.push(value),
        now: () => now,
        setTimeoutImpl: (callback, ms) => { timers.push({ callback, ms }); return timers.length; },
        clearTimeoutImpl: () => {},
        microphoneAvailable: () => true,
    });
    return { transcriber, connections, committed, partial, timers, get tokenRequests() { return tokenRequests; }, setNow: (value) => { now = value; } };
}

async function ready(harness) {
    const pending = harness.transcriber.connect();
    await new Promise((resolve) => setImmediate(resolve));
    harness.connections[0].emit(events.SESSION_STARTED);
    await pending;
}

test("Scribe requests one token, starts muted and commits one final transcript", async () => {
    const h = createHarness();
    await ready(h);
    const connection = h.connections[0];
    assert.equal(connection.muteCalls, 1);
    assert.equal(h.transcriber.state, VoiceState.READY);
    await h.transcriber.startListening();
    assert.equal(connection.unmuteCalls, 1);
    connection.emit(events.PARTIAL_TRANSCRIPT, { text: "частичная фраза" });
    assert.deepEqual(h.partial, ["частичная фраза"]);
    h.setNow(1_500);
    assert.equal(h.transcriber.stopListeningAndCommit(), true);
    assert.equal(connection.muteCalls, 2);
    assert.equal(connection.commitCalls, 1);
    connection.emit(events.COMMITTED_TRANSCRIPT, { text: "  готовая фраза  " });
    connection.emit(events.COMMITTED_TRANSCRIPT, { text: "дубликат" });
    assert.deepEqual(h.committed, ["готовая фраза"]);
    assert.equal(h.transcriber.state, VoiceState.READY);
});

test("Scribe ждёт MediaStreamTrack, если session_started пришёл раньше микрофона", async () => {
    const h = createHarness();
    const pending = h.transcriber.connect();
    await new Promise((resolve) => setImmediate(resolve));
    const connection = h.connections[0];
    let unavailableTrack = true;
    connection.mute = () => {
        connection.muteCalls += 1;
        if (unavailableTrack) throw new Error("Cannot mute audio without an active microphone MediaStreamTrack.");
    };

    connection.emit(events.SESSION_STARTED);
    assert.equal(h.transcriber.state, VoiceState.CONNECTING);
    assert.equal(connection.muteCalls, 1);

    unavailableTrack = false;
    h.timers.find((timer) => timer.ms === 25).callback();
    await pending;
    assert.equal(h.transcriber.state, VoiceState.READY);
    assert.equal(connection.muteCalls, 2);
});

test("public runtime configuration changes token endpoint only while disconnected", () => {
    const h = createHarness();
    h.transcriber.configure({ tokenUrl: "http://localhost:8080/api/stt/token", languageCode: undefined });
    assert.equal(h.transcriber.tokenUrl, "http://localhost:8080/api/stt/token");
    assert.equal(h.transcriber.languageCode, undefined);
});

test("microphone permission probe releases its temporary stream", async () => {
    let stopped = 0;
    let unlocked = 0;
    const transcriber = new PushToTalkTranscriber({
        microphoneAvailable: () => true,
        audioUnlocker: () => { unlocked += 1; return null; },
        microphonePermissionRequester: async () => { stopped += 1; },
        onError: () => {}
    });
    assert.equal(await transcriber.requestMicrophonePermission(), true);
    assert.equal(stopped, 1);
    assert.equal(unlocked, 1);
});

test("Scribe ошибки используют поле error и дают безопасный понятный статус", async () => {
    assert.match(
        microphoneErrorMessage(Object.assign(new Error("AudioWorklet failed"), { name: "error" })),
        /подготовить аудио/i
    );
    assert.match(
        microphoneErrorMessage(Object.assign(new Error("quota"), { name: "quota_exceeded" })),
        /лимит elevenlabs/i
    );

    const h = createHarness();
    const messages = [];
    h.transcriber.onError = (message) => messages.push(message);
    await ready(h);
    h.connections[0].emit(events.ERROR, { message_type: "auth_error", error: "rejected" });
    assert.match(messages.at(-1), /API-ключ/i);
});

test("short press and unsolicited transcript never create a user turn", async () => {
    const h = createHarness();
    await ready(h);
    const connection = h.connections[0];
    connection.emit(events.COMMITTED_TRANSCRIPT, { text: "неожиданно" });
    await h.transcriber.startListening();
    h.setNow(1_050);
    assert.equal(h.transcriber.stopListeningAndCommit(), false);
    connection.emit(events.COMMITTED_TRANSCRIPT, { text: "ещё раз" });
    assert.deepEqual(h.committed, []);
    assert.equal(connection.commitCalls, 1);
});

test("empty committed transcript returns to ready without submit", async () => {
    const h = createHarness();
    await ready(h);
    await h.transcriber.startListening();
    h.setNow(1_500);
    h.transcriber.stopListeningAndCommit();
    h.connections[0].emit(events.COMMITTED_TRANSCRIPT, { text: "   " });
    assert.deepEqual(h.committed, []);
    assert.equal(h.transcriber.state, VoiceState.READY);
});

test("commit timeout never submits partial speech and exits committing state", async () => {
    const h = createHarness();
    await ready(h);
    await h.transcriber.startListening();
    h.setNow(1_500);
    h.transcriber.stopListeningAndCommit();
    h.timers.find((timer) => timer.ms === 4_000).callback();
    assert.equal(h.transcriber.state, VoiceState.ERROR);
    assert.deepEqual(h.committed, []);
});

test("stale connection callbacks are ignored and close releases active connection", async () => {
    const h = createHarness();
    await ready(h);
    const old = h.connections[0];
    h.transcriber.close();
    assert.equal(old.closeCalls, 1);
    assert.equal(h.transcriber.state, VoiceState.DISCONNECTED);
    const reconnect = h.transcriber.connect();
    await new Promise((resolve) => setImmediate(resolve));
    const current = h.connections[1];
    old.emit(events.COMMITTED_TRANSCRIPT, { text: "устаревшее" });
    current.emit(events.SESSION_STARTED);
    await reconnect;
    assert.deepEqual(h.committed, []);
    assert.equal(h.tokenRequests, 2);
});

test("unexpected Scribe error reconnects with a fresh single-use token", async () => {
    const h = createHarness();
    await ready(h);
    const failed = h.connections[0];
    failed.emit(events.ERROR, { message: "network interrupted" });
    const retry = h.timers.find((timer) => timer.ms === 250);
    retry.callback();
    await new Promise((resolve) => setImmediate(resolve));
    h.connections[1].emit(events.SESSION_STARTED);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(h.tokenRequests, 2);
    assert.equal(h.transcriber.state, VoiceState.READY);
});
