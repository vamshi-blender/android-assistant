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

for (const [toolName, args] of [
  ["get_device_status", { includeWifiNetworks: true }],
  ["set_device_setting", { setting: "bluetooth", enabled: true }],
  ["switch_wifi_network", { ssid: "Home" }],
] as const) {
  test(`${toolName} pauses for the phone and resumes with its result`, async () => {
    let modelCalls = 0;
    const model: Model = {
      async getResponse() { throw new Error("Only streaming expected"); },
      async *getStreamedResponse() {
        modelCalls++;
        yield { type: "response_done", response: {
          id: `resp_${modelCalls}`, usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
          output: modelCalls === 1
            ? [{ type: "function_call", id: "fc_1", callId: "call_1", name: toolName, arguments: JSON.stringify(args) }]
            : [{ type: "message", id: "msg_1", role: "assistant", status: "completed", content: [{ type: "output_text", text: "Done." }] }],
        } };
      },
    };
    assistantAgent.model = model;
    const events: any[] = [];
    await streamAssistantResponse("Check my phone", "conv_test", context, e => events.push(e));
    const started = events.find(e => e.type === "tool.execution.started");
    assert.equal(started.name, toolName);
    assert.deepEqual(started.arguments, args);
    const pending = events.find(e => e.type === "client.tools.requested");
    // Settings tools go to the phone under their own name, with untouched arguments.
    assert.deepEqual(pending.requests, [{ callId: "call_1", name: toolName, arguments: args }]);

    const resumed: any[] = [];
    await streamAssistantResponse("", undefined, {
      ...context, toolResults: { call_1: { status: "succeeded", message: "{\"ok\":true}" } },
    }, e => resumed.push(e), pending.continuation);
    assert.equal(modelCalls, 2);
    assert.equal(JSON.parse(resumed.find(e => e.type === "tool.execution.completed").output).status, "succeeded");
    assert.equal(resumed.at(-1).type, "response.completed");
  });
}

test("every agent tool list exposes the phone-settings tools", async () => {
  const registry = await import("./tool-registry.js");
  const names = (tools: readonly any[]) => tools.map(t => t.name ?? t.type);
  for (const list of [registry.openAiChatTools, registry.groqChatTools, registry.groqLiveTools, registry.openAiLiveTools]) {
    for (const name of ["get_device_status", "set_device_setting", "switch_wifi_network"]) {
      assert.ok(names(list).includes(name), `${name} missing from a tool list`);
    }
  }
  assert.deepEqual(registry.toDeviceRequest("manage_device_clock", { action: "set_alarm", hour: 7 }),
    { name: "set_alarm", arguments: { hour: 7 } });
  assert.equal(registry.toDeviceRequest("search_web", {}), undefined);
});

test("Groq tools avoid strict schemas with an empty required list", async () => {
  // Groq answers 400 "'required' present but 'properties' is missing" for these,
  // which broke every Groq live delegation.
  const registry = await import("./tool-registry.js");
  for (const list of [registry.groqChatTools, registry.groqLiveTools]) {
    for (const tool of list as any[]) {
      const schema = tool.parameters;
      if (!schema || typeof schema !== "object") continue;
      const emptyRequired = Array.isArray(schema.required) && schema.required.length === 0;
      assert.ok(!(tool.strict && emptyRequired), `${tool.name} is strict with an empty required list`);
    }
  }
});
