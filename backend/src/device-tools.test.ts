import assert from "node:assert/strict";
import { test } from "node:test";
import { setTracingDisabled, type Model, type ModelRequest } from "@openai/agents";
import { sealContinuation, openContinuation } from "./continuation.js";

process.env.OPENAI_API_KEY = "test-only-no-network";
setTracingDisabled(true);
const { assistantAgent } = await import("./agent.js");
const { streamAssistantResponse } = await import("./chat.js");
const context = { deviceTime: { epochMillis: Date.now(), timeZoneId: "Asia/Kolkata", receivedAtServerEpochMillis: Date.now() } };

test("encrypted continuations reject tampering and expiry", () => {
  const value = { conversationId: "conv_test", state: "serialized state" };
  const token = sealContinuation(value);
  assert.equal(openContinuation(token).state, value.state);
  const bytes = Buffer.from(token, "base64url");
  bytes[30] ^= 1;
  assert.throws(() => openContinuation(bytes.toString("base64url")));
  const now = Date.now;
  try { Date.now = () => now() + 301_000; assert.throws(() => openContinuation(token)); }
  finally { Date.now = now; }
});

for (const status of ["succeeded", "failed", "unknown", "requires_user_action"] as const) {
  test(`pause then resume with Android ${status} result`, async () => {
    let modelCalls = 0;
    const model: Model = {
      async getResponse() { throw new Error("Only streaming expected"); },
      async *getStreamedResponse(request: ModelRequest) {
        modelCalls++;
        if (modelCalls > 1) {
          assert.match(JSON.stringify(request.input), new RegExp(status));
          assert.doesNotMatch(JSON.stringify(request.input), /dispatched_to_android/);
        }
        yield { type: "response_done", response: {
          id: `resp_${modelCalls}`, usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
          output: modelCalls === 1
            ? [{ type: "function_call", id: "fc_1", callId: "call_1", name: "manage_device_clock", arguments: JSON.stringify({ action: "set_alarm", hour: 7, minute: 30 }) }]
            : [{ type: "message", id: "msg_1", role: "assistant", status: "completed", content: [{ type: "output_text", text: "Device result received." }] }],
        } };
      },
    };
    assistantAgent.model = model;
    const events: any[] = [];
    await streamAssistantResponse("Set alarm", "conv_test", context, e => events.push(e));
    assert.equal(modelCalls, 1);
    assert.equal(events.some(e => e.type === "response.completed"), false);
    assert.equal(events.some(e => e.type === "tool.execution.completed"), false);
    const pending = events.find(e => e.type === "client.tools.requested");
    assert.equal(pending.requests[0].callId, "call_1");
    assert.equal(pending.requests[0].name, "set_alarm");
    await assert.rejects(streamAssistantResponse("", undefined, { ...context, toolResults: { wrong: { status, message: "Device reply" } } }, () => {}, pending.continuation), /match the pending/);
    const resumed: any[] = [];
    await streamAssistantResponse("", undefined, { ...context, toolResults: { call_1: { status, message: "Device reply" } } }, e => resumed.push(e), pending.continuation);
    assert.equal(modelCalls, 2);
    assert.equal(resumed.some(e => e.type === "client.tools.requested"), false);
    const completed = resumed.find(e => e.type === "tool.execution.completed");
    assert.equal(JSON.parse(completed.output).status, status);
    assert.equal(resumed.at(-1).type, "response.completed");
  });
}
