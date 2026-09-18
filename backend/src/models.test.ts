import assert from "node:assert/strict";
import test from "node:test";
import { groqAssistantAgent } from "./agent.js";
import { createGroqRunner, GROQ_MODEL } from "./models.js";

test("Groq runner uses the Agents SDK Chat Completions adapter", async (t) => {
  const originalKey = process.env.GROQ_API_KEY;
  process.env.GROQ_API_KEY = "test-groq-key";
  t.mock.method(globalThis, "fetch", async (input: string | URL | Request, init?: RequestInit) => {
    assert.equal(String(input), "https://api.groq.com/openai/v1/chat/completions");
    const headers = new Headers(init?.headers);
    assert.equal(headers.get("authorization"), "Bearer test-groq-key");
    const body = JSON.parse(String(init?.body));
    assert.equal(body.model, GROQ_MODEL);
    assert.equal(body.stream, true);
    assert.equal(body.reasoning_effort, "low");
    assert.equal(body.verbosity, undefined);
    assert.equal(body.include, undefined);
    assert.equal(body.truncation, undefined);
    const chunks = [
      { id: "chatcmpl_test", object: "chat.completion.chunk", created: 1, model: GROQ_MODEL,
        choices: [{ index: 0, delta: { role: "assistant", content: "Hello!" }, finish_reason: null }] },
      { id: "chatcmpl_test", object: "chat.completion.chunk", created: 1, model: GROQ_MODEL,
        choices: [{ index: 0, delta: {}, finish_reason: "stop" }] },
    ];
    return new Response(`${chunks.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`).join("")}data: [DONE]\n\n`, {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    });
  });

  try {
    const stream = await createGroqRunner().run(groqAssistantAgent, "Hi", { stream: true });
    for await (const _event of stream) {
      // Consume the complete SDK stream.
    }
    await stream.completed;
    assert.equal(stream.finalOutput, "Hello!");
  } finally {
    if (originalKey === undefined) delete process.env.GROQ_API_KEY;
    else process.env.GROQ_API_KEY = originalKey;
  }
});
