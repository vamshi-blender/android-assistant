import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import test from "node:test";
import { handleLive } from "./live.js";

test("Live endpoint authenticates and creates conversation-only WebRTC sessions", async (t) => {
  const originalAppKey = process.env.APP_API_KEY;
  const originalOpenAIKey = process.env.OPENAI_API_KEY;
  process.env.APP_API_KEY = "test-app-key";
  process.env.OPENAI_API_KEY = "test-openai-key";
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
    assert.deepEqual(body.session.delegation, { type: "client" });
    assert.equal(body.session.tools, undefined);
    assert.equal(body.session.audio, undefined);
    assert.deepEqual(body.transport, { type: "webrtc", sdp: "offer" });
    return Response.json(upstreamStatus === 201
      ? { session: { id: "live_test" }, transport: { type: "webrtc", sdp: "answer" } }
      : { error: { message: "Private upstream detail" } }, { status: upstreamStatus });
  });
  const post = (body: string, key = "test-app-key") => realFetch(endpoint, {
    method: "POST", headers: { "Content-Type": "application/json", "X-API-Key": key }, body,
  });
  try {
    assert.equal((await realFetch(endpoint)).status, 405);
    assert.equal((await post('{"sdp":"offer"}', "wrong-key")).status, 401);
    assert.equal((await post("invalid-json")).status, 400);
    assert.equal((await post('{"sdp":"  "}')).status, 400);
    assert.equal((await post(JSON.stringify({ sdp: "x".repeat(65_536) }))).status, 413);
    assert.equal(calls, 0);

    const result = await post('{"sdp":"offer", "session":{"model":"untrusted"}}');
    assert.equal(result.status, 201);
    assert.deepEqual(await result.json(), {
      session: { id: "live_test" }, transport: { type: "webrtc", sdp: "answer" },
    });
    assert.equal(calls, 1);

    upstreamStatus = 429;
    const limited = await post('{"sdp":"offer"}');
    assert.equal(limited.status, 429);
    assert.doesNotMatch(await limited.text(), /Private upstream detail/);
    delete process.env.OPENAI_API_KEY;
    assert.equal((await post('{"sdp":"offer"}')).status, 503);
    assert.equal(calls, 2);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    if (originalAppKey === undefined) delete process.env.APP_API_KEY;
    else process.env.APP_API_KEY = originalAppKey;
    if (originalOpenAIKey === undefined) delete process.env.OPENAI_API_KEY;
    else process.env.OPENAI_API_KEY = originalOpenAIKey;
  }
});
