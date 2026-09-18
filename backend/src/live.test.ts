import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import test from "node:test";
import { handleLive } from "./live.js";

test("Live endpoint authenticates and creates delegated Clock WebRTC sessions", async (t) => {
  const originalAppKey = process.env.APP_API_KEY;
  const originalOpenAIKey = process.env.OPENAI_API_KEY;
  const originalGroqKey = process.env.GROQ_API_KEY;
  process.env.APP_API_KEY = "test-app-key";
  process.env.OPENAI_API_KEY = "test-openai-key";
  process.env.GROQ_API_KEY = "test-groq-key";
  const realFetch = globalThis.fetch;
  const server = createServer(handleLive);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert(address && typeof address !== "string");
  const endpoint = `http://127.0.0.1:${address.port}/api/live`;
  let calls = 0;
  let upstreamStatus = 201;
  t.mock.method(globalThis, "fetch", async (url: string | URL | Request, init?: RequestInit) => {
    calls++;
    assert.equal(url, "https://api.openai.com/v1/live/sessions");
    assert.equal((init?.headers as Record<string, string>).Authorization, "Bearer test-openai-key");
    const body = JSON.parse(init?.body as string);
    assert.equal(body.session.model, "gpt-live-1");
    assert.match(body.session.instructions, /enthusiastic, energetic, and upbeat/);
    assert.match(body.session.instructions, /brisk, fast pace/);
    assert.deepEqual(body.session.audio, { output: { voice: "delta" } });
    if (body.session.delegation.type === "responses") {
      assert.equal(body.session.delegation.responses.model, "gpt-5.6-terra");
      assert.equal(body.session.delegation.responses.tool_choice, "auto");
      assert.equal(body.session.delegation.responses.parallel_tool_calls, false);
      assert.deepEqual(body.session.delegation.responses.tools.map((tool: { name: string }) => tool.name), [
        "get_device_time", "set_alarm", "start_timer", "show_alarms", "show_timers", "snooze_alarm",
        "dismiss_alarm", "dismiss_expired_timers", "end_session",
      ]);
      assert.match(body.session.delegation.responses.instructions, /Asia\/Kolkata/);
      assert.match(body.session.delegation.responses.instructions, /Only report success/);
    } else {
      assert.deepEqual(body.session.delegation, { type: "client" });
    }
    assert.match(body.session.instructions, /one assistant/);
    assert.match(body.session.instructions, /current time, date, or day/);
    assert.match(body.session.instructions, /end, stop, close, or hang up/);
    assert.equal(body.session.tools, undefined);
    assert.deepEqual(body.transport, { type: "webrtc", sdp: "offer" });
    return Response.json(upstreamStatus === 201
      ? { session: { id: "live_test" }, transport: { type: "webrtc", sdp: "answer" } }
      : { error: { message: "Private upstream detail" } }, { status: upstreamStatus });
  });
  const post = (body: string, key = "test-app-key") => realFetch(endpoint, {
    method: "POST", headers: { "Content-Type": "application/json", "X-API-Key": key }, body,
  });
  const validRequest = JSON.stringify({
    sdp: "offer",
    deviceTime: { epochMillis: Date.parse("2026-09-18T12:00:00Z"), timeZoneId: "Asia/Kolkata" },
  });
  try {
    assert.equal((await realFetch(endpoint)).status, 405);
    assert.equal((await post('{"sdp":"offer"}', "wrong-key")).status, 401);
    assert.equal((await post("invalid-json")).status, 400);
    assert.equal((await post('{"sdp":"  "}')).status, 400);
    assert.equal((await post(JSON.stringify({ sdp: "x".repeat(65_536) }))).status, 413);
    assert.equal((await post('{"sdp":"offer"}')).status, 400);
    assert.equal((await post('{"sdp":"offer","deviceTime":{"epochMillis":1,"timeZoneId":"Invalid/Zone"}}')).status, 400);
    assert.equal(calls, 0);

    const result = await post(validRequest);
    assert.equal(result.status, 201);
    assert.deepEqual(await result.json(), {
      session: { id: "live_test" }, transport: { type: "webrtc", sdp: "answer" },
      greeting: {
        instructions: "Greet the user immediately in English without waiting for them to speak. Give one short, energetic welcome and mention that you can chat and help with alarms and timers. Then pause and listen. Do not repeat this greeting later in the session.",
        begin: "Begin the conversation now, following the greeting instructions provided.",
      },
    });
    assert.equal(calls, 1);

    const groqResult = await post(JSON.stringify({
      ...JSON.parse(validRequest),
      model: "groq",
    }));
    assert.equal(groqResult.status, 201);
    assert.equal(calls, 2);

    upstreamStatus = 429;
    const limited = await post(validRequest);
    assert.equal(limited.status, 429);
    assert.doesNotMatch(await limited.text(), /Private upstream detail/);
    delete process.env.OPENAI_API_KEY;
    assert.equal((await post(validRequest)).status, 503);
    assert.equal(calls, 3);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    if (originalAppKey === undefined) delete process.env.APP_API_KEY;
    else process.env.APP_API_KEY = originalAppKey;
    if (originalOpenAIKey === undefined) delete process.env.OPENAI_API_KEY;
    else process.env.OPENAI_API_KEY = originalOpenAIKey;
    if (originalGroqKey === undefined) delete process.env.GROQ_API_KEY;
    else process.env.GROQ_API_KEY = originalGroqKey;
  }
});
