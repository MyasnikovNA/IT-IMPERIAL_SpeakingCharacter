import test from "node:test";
import assert from "node:assert/strict";
import { createPushToTalkController } from "./push-to-talk-controller.js";

test("controller forwards only committed voice text to shared submit", async () => {
    const listeners = new Map();
    const originalWindow = globalThis.window;
    globalThis.window = { addEventListener() {} };
    const button = { disabled: false, textContent: "", classList: { toggle() {} }, setAttribute() {}, addEventListener: (name, callback) => listeners.set(name, callback), setPointerCapture() {} };
    const transcriptElement = { textContent: "" };
    let options;
    let submitted;
    const fake = { state: "READY", configure() {}, connect: async () => {}, ensureConnected: async () => {}, close() {}, startListening: async () => true, stopListeningAndCommit: () => true };
    const controller = createPushToTalkController({
        button, transcriptElement, ensureAvatarConnected: () => true, interrupt: () => {},
        submit: async (value, meta) => { submitted = { value, meta }; },
        setStatus: () => {}, transcriberFactory: (received) => { options = received; return fake; }
    });
    await controller.connect();
    options.onPartial("частично");
    assert.equal(transcriptElement.textContent, "частично");
    await options.onCommitted("готово", { turnId: 1, maxDuration: false, releasedAt: 1234 });
    assert.deepEqual(submitted, { value: "готово", meta: { source: "voice", releasedAt: 1234 } });
    globalThis.window = originalWindow;
});

test("pointer release during delayed reconnect cannot leave microphone listening", async () => {
    const listeners = new Map();
    const originalWindow = globalThis.window;
    globalThis.window = { addEventListener() {} };
    const button = { disabled: false, textContent: "", classList: { toggle() {} }, setAttribute() {}, addEventListener: (name, callback) => listeners.set(name, callback), setPointerCapture() {} };
    let resolveStart;
    let stopCalls = 0;
    const fake = {
        state: "READY", configure() {}, connect: async () => {}, ensureConnected: async () => {}, close() {},
        startListening: () => new Promise((resolve) => { resolveStart = () => { fake.state = "LISTENING"; resolve(true); }; }),
        stopListeningAndCommit: () => {
            if (fake.state !== "LISTENING") return false;
            fake.state = "READY";
            stopCalls += 1;
            return true;
        }
    };
    const controller = createPushToTalkController({
        button, transcriptElement: { textContent: "" }, ensureAvatarConnected: () => true, interrupt: () => {},
        submit: async () => {}, setStatus: () => {}, transcriberFactory: () => fake
    });
    await controller.connect();
    const down = listeners.get("pointerdown")({ preventDefault() {}, pointerId: 7 });
    await Promise.resolve();
    await Promise.resolve();
    listeners.get("pointerup")({ preventDefault() {}, pointerId: 7 });
    resolveStart(true);
    await down;
    assert.equal(stopCalls, 1);
    globalThis.window = originalWindow;
});

test("failed microphone recovery does not interrupt the avatar", async () => {
    const listeners = new Map();
    const originalWindow = globalThis.window;
    globalThis.window = { addEventListener() {} };
    const button = { disabled: false, textContent: "", classList: { toggle() {} }, setAttribute() {}, addEventListener: (name, callback) => listeners.set(name, callback), setPointerCapture() {} };
    let interrupts = 0;
    const fake = { state: "ERROR", configure() {}, connect: async () => {}, ensureConnected: async () => { throw new Error("mic denied"); }, close() {}, startListening: async () => true, stopListeningAndCommit: () => false };
    const controller = createPushToTalkController({
        button, transcriptElement: { textContent: "" }, ensureAvatarConnected: () => true, interrupt: () => { interrupts += 1; },
        submit: async () => {}, setStatus: () => {}, transcriberFactory: () => fake
    });
    await controller.connect();
    await listeners.get("pointerdown")({ preventDefault() {}, pointerId: 3 });
    assert.equal(interrupts, 0);
    globalThis.window = originalWindow;
});
