import type { IncomingMessage, ServerResponse } from "node:http";
import {
  streamAssistantResponse,
  type ChatStreamEvent,
} from "./chat.js";

type ChatRequest = IncomingMessage & { body?: unknown };

function sendEvent(response: ServerResponse, event: ChatStreamEvent): void {
  const { type, ...data } = event;
  response.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
}

async function readBody(request: ChatRequest): Promise<unknown> {
  if (request.body !== undefined) return request.body;

  let raw = "";
  for await (const chunk of request) raw += chunk;
  return raw ? JSON.parse(raw) : {};
}

export async function handleChat(
  request: ChatRequest,
  response: ServerResponse,
): Promise<void> {
  if (request.method !== "POST") {
    response.writeHead(405, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "Method not allowed" }));
    return;
  }

  try {
    const body = (await readBody(request)) as {
      message?: unknown;
      conversationId?: unknown;
      deviceTime?: unknown;
    };
    const message = typeof body.message === "string" ? body.message.trim() : "";
    const conversationId =
      typeof body.conversationId === "string" ? body.conversationId : undefined;
    const rawDeviceTime = body.deviceTime as
      | { epochMillis?: unknown; timeZoneId?: unknown }
      | undefined;

    if (!message) {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "message is required" }));
      return;
    }

    if (
      typeof rawDeviceTime?.epochMillis !== "number" ||
      !Number.isFinite(rawDeviceTime.epochMillis) ||
      typeof rawDeviceTime.timeZoneId !== "string" ||
      !rawDeviceTime.timeZoneId
    ) {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "valid deviceTime is required" }));
      return;
    }

    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });
    response.flushHeaders();

    await streamAssistantResponse(
      message,
      conversationId,
      {
        deviceTime: {
          epochMillis: rawDeviceTime.epochMillis,
          timeZoneId: rawDeviceTime.timeZoneId,
          receivedAtServerEpochMillis: Date.now(),
        },
      },
      (event) => sendEvent(response, event),
    );
    response.end();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown server error";
    if (!response.headersSent) {
      response.writeHead(500, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: message }));
    } else {
      sendEvent(response, { type: "response.error", message });
      response.end();
    }
  }
}
