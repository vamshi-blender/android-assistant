import type { IncomingMessage, ServerResponse } from "node:http";
import { authenticateApiRequest } from "./auth.js";

type LiveRequest = IncomingMessage & { body?: unknown };
const MAX_BODY_BYTES = 64 * 1024;

function reply(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(body));
}

export async function handleLive(request: LiveRequest, response: ServerResponse): Promise<void> {
  if (request.method !== "POST") {
    reply(response, 405, { error: "Method not allowed" });
    return;
  }
  if (!authenticateApiRequest(request, response)) return;

  let body: unknown;
  try {
    if (request.body !== undefined) {
      body = request.body;
      if (Buffer.byteLength(JSON.stringify(body)) > MAX_BODY_BYTES) {
        reply(response, 413, { error: "Session request is too large" });
        return;
      }
    } else {
      const chunks: Buffer[] = [];
      let size = 0;
      for await (const chunk of request) {
        const bytes = Buffer.from(chunk);
        size += bytes.length;
        if (size > MAX_BODY_BYTES) {
          reply(response, 413, { error: "Session request is too large" });
          return;
        }
        chunks.push(bytes);
      }
      body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    }
  } catch {
    reply(response, 400, { error: "Invalid session request" });
    return;
  }

  const sdp = (body as { sdp?: unknown } | null)?.sdp;
  if (typeof sdp !== "string" || !sdp.trim()) {
    reply(response, 400, { error: "An SDP offer is required" });
    return;
  }
  if (!process.env.OPENAI_API_KEY) {
    reply(response, 503, { error: "Live voice is not configured on the server" });
    return;
  }

  try {
    // Use the Live REST endpoint directly; the existing SDK predates Live.
    const upstream = await fetch("https://api.openai.com/v1/live/sessions", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
        "Content-Type": "application/json",
      },
      signal: AbortSignal.timeout(30_000),
      body: JSON.stringify({
        session: {
          model: "gpt-live-1",
          instructions: "You are a friendly conversational voice assistant. Keep replies natural and concise. Respond directly to the user and listen to interruptions. This session is conversation only: no backend, tools, current-information lookups, or device actions are available. Do not delegate or claim to perform actions. If asked for something unavailable, briefly explain and continue the conversation.",
          delegation: { type: "client" },
        },
        transport: { type: "webrtc", sdp },
      }),
    });
    if (!upstream.ok) {
      console.error("Live session creation failed", upstream.status);
      reply(response, upstream.status === 429 ? 429 : 502, {
        error: upstream.status === 429
          ? "Live voice limit reached. Please try again later."
          : "Unable to start live voice. Check the server's GPT-Live access.",
      });
      return;
    }
    const result = await upstream.json() as { session?: { id?: string }; transport?: { sdp?: string } };
    if (!result.session?.id || !result.transport?.sdp) throw new Error("Invalid Live response");
    reply(response, 201, { session: { id: result.session.id }, transport: { type: "webrtc", sdp: result.transport.sdp } });
  } catch {
    reply(response, 502, { error: "Unable to connect to live voice. Please try again." });
  }
}
