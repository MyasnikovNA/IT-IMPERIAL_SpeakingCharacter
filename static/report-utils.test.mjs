import test from "node:test";
import assert from "node:assert/strict";
import {
    buildRepeatScenarioState,
    calculateTrainingStats,
    classifyReportState,
    evidenceTargetId,
    hasEvidenceGenerationId,
    isUserMessage,
    transcriptMessageDomId
} from "./report-utils.js";

test("report recognises lowercase backend message roles and calculates user turns", () => {
    const messages = [{ role: "user" }, { role: "model" }, { role: "user" }];
    assert.equal(isUserMessage({ role: "user" }), true);
    assert.equal(isUserMessage({ role: "USER" }), false);
    assert.equal(isUserMessage({ role: "model" }), false);
    assert.equal(calculateTrainingStats({ messages }).userTurns, 2);
});

test("report evidence accepts only numeric generation ids and targets user rows", () => {
    assert.equal(hasEvidenceGenerationId({ evidenceGenerationId: 3 }), true);
    assert.equal(hasEvidenceGenerationId({ evidenceGenerationId: null }), false);
    assert.equal(hasEvidenceGenerationId({}), false);
    assert.equal(hasEvidenceGenerationId({ evidenceGenerationId: "3" }), false);
    assert.equal(evidenceTargetId({ evidenceGenerationId: 3 }), "user-message-3");
    assert.equal(evidenceTargetId({}), null);
});

test("report transcript ids stay unique for messages in one generation", () => {
    assert.equal(transcriptMessageDomId({ role: "user", generationId: 3 }), "user-message-3");
    assert.equal(transcriptMessageDomId({ role: "model", generationId: 3 }), "trainer-message-3");
});

test("report preserves preset display metadata but never recreates uploaded markdown", () => {
    const preset = buildRepeatScenarioState({ scenarioSource: "PRESET", scenario: { id: "sales", title: "Продажа", criteria: ["A"], stages: [{ id: "one" }] } });
    assert.deepEqual(preset, { selection: { presetId: "sales" }, display: { title: "Продажа", criteria: ["A"], stageCount: 1 }, canRepeatDirectly: true });
    assert.equal(buildRepeatScenarioState({ scenarioSource: "UPLOADED", scenario: { id: "custom" } }).canRepeatDirectly, false);
    assert.deepEqual(buildRepeatScenarioState({}), { selection: null, display: null, canRepeatDirectly: true });
});

test("report state classifies only transitional finished states as polling", () => {
    assert.equal(classifyReportState({ status: "ACTIVE", reportStatus: "GENERATING" }), "ACTIVE");
    assert.equal(classifyReportState({ status: "FINISHED", reportStatus: "GENERATING" }), "PENDING");
    assert.equal(classifyReportState({ status: "FINISHED", reportStatus: "NOT_STARTED" }), "PENDING");
    assert.equal(classifyReportState({ status: "FINISHED", reportStatus: "READY", report: {} }), "READY");
    assert.equal(classifyReportState({ status: "FINISHED", reportStatus: "FAILED" }), "FAILED");
});

test("report statistics include duration and model interruptions", () => {
    const stats = calculateTrainingStats({
        createdAt: "2026-01-01T00:00:00Z", finishedAt: "2026-01-01T00:01:05Z",
        messages: [{ role: "user" }, { role: "model", interrupted: true }, { role: "model", interrupted: false }]
    });
    assert.deepEqual(stats, { duration: "1 мин. 5 сек.", userTurns: 1, interruptions: 1 });
});
