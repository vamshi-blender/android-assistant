import type { IncomingMessage, ServerResponse } from "node:http";
import { Agent, RunContext, RunState } from "@openai/agents";
import { z } from "zod";
import { authenticateApiRequest } from "./auth.js";
import { endLiveSession, manageDeviceClock, type DeviceContext } from "./agent.js";
import { openContinuation, sealContinuation } from "./continuation.js";
import { createGroqRunner, parseModelSelection } from "./models.js";

type LiveDelegateRequest = IncomingMessage & { body?: unknown };
const MAX_BODY_BYTES = 128 * 1024;

const liveDelegateAgent = new Agent<DeviceContext>({
  name: "Live mobile assistant backend",
  model: "openai/gpt-oss-20b",
  instructions: [
    "You are the backend reasoning and action component of one live voice assistant. Never mention delegation, providers, models, tools, or another agent.",
    "The input contains the live conversation transcript and an authoritative current device-time tag. Transcripts may contain mistakes or later corrections; use the latest context.",
    "Answer questions about the current time, date, or day from the supplied device time.",
    "Use manage_device_clock for every explicit supported alarm or timer request. Ask for a missing essential detail rather than guessing.",
    "Use end_session once when the user clearly asks to end, stop, close, or hang up the live conversation. Do not use it merely for 'stop talking'.",
    "Only report a device action as successful when its returned status is succeeded. Never retry an unknown action automatically.",
    "Return only a short, natural result for the voice assistant to say. After end_session returns, give one brief friendly goodbye.",
  ].join("\n\n"),
  tools: [manageDeviceClock, endLiveSession],
});

async function readBody(request: LiveDelegateRequest): Promise<unknown> {
  if (request.body !== undefined) {
    if (Buffer.byteLength(JSON.stringify(request.body)) > MAX_BODY_BYTES) {
      throw new Error("Delegation request is too large");
    }
    return request.body;
  }
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const bytes = Buffer.from(chunk);
    size += bytes.length;
    if (size > MAX_BODY_BYTES) throw new Error("Delegation request is too large");
    chunks.push(bytes);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function reply(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(body));
}

function currentTimeTag(deviceTime: DeviceContext["deviceTime"]): string {
  const formatted = new Intl.DateTimeFormat("en-IN", {
    dateStyle: "full",
    timeStyle: "long",
    timeZone: deviceTime.timeZoneId,
  }).format(new Date(deviceTime.epochMillis));
  return `<current_time>Device time (${deviceTime.timeZoneId}): ${formatted}</current_time>`;
}

export async function handleLiveDelegate(
  request: LiveDelegateRequest,
  response: ServerResponse,
): Promise<void> {
  if (request.method !== "POST") {
    reply(response, 405, { error: "Method not allowed" });
    return;
  }
  if (!authenticateApiRequest(request, response)) return;

  try {
    const body = (await readBody(request)) as {
      model?: unknown;
      transcript?: unknown;
      continuation?: unknown;
      toolResults?: unknown;
      deviceTime?: unknown;
    };
    if (parseModelSelection(body.model) !== "groq") {
      reply(response, 400, { error: "Client delegation requires the Groq model" });
      return;
    }
    if (!process.env.GROQ_API_KEY?.trim()) {
      reply(response, 503, { error: "Groq is not configured on the server" });
      return;
    }
    const transcript = typeof body.transcript === "string" ? body.transcript.trim() : "";
    const continuation = typeof body.continuation === "string" ? body.continuation : undefined;
    if (!transcript && !continuation) {
      reply(response, 400, { error: "Transcript is required" });
      return;
    }

    const rawDeviceTime = body.deviceTime as
      | { epochMillis?: unknown; timeZoneId?: unknown }
      | undefined;
    if (typeof rawDeviceTime?.epochMillis !== "number" ||
        !Number.isFinite(rawDeviceTime.epochMillis) ||
        typeof rawDeviceTime.timeZoneId !== "string" || !rawDeviceTime.timeZoneId) {
      reply(response, 400, { error: "Valid device time is required" });
      return;
    }
    new Intl.DateTimeFormat("en", { timeZone: rawDeviceTime.timeZoneId }).format(0);

    const toolResults = body.toolResults === undefined ? undefined : z.record(
      z.string(),
      z.object({
        status: z.enum(["succeeded", "failed", "unknown", "requires_user_action"]),
        message: z.string().max(2_000),
      }),
    ).parse(body.toolResults);
    const context: DeviceContext = {
      toolResults,
      deviceTime: {
        epochMillis: rawDeviceTime.epochMillis,
        timeZoneId: rawDeviceTime.timeZoneId,
        receivedAtServerEpochMillis: Date.now(),
      },
    };

    let input: string | RunState<DeviceContext, typeof liveDelegateAgent> =
      `${currentTimeTag(context.deviceTime)}\n\n<live_transcript>\n${transcript}\n</live_transcript>`;
    if (continuation) {
      const saved = openContinuation(continuation);
      if (saved.model !== "groq") throw new Error("The continuation model does not match");
      const state = await RunState.fromStringWithContext(
        liveDelegateAgent,
        saved.state,
        new RunContext(context),
        { contextStrategy: "replace" },
      );
      const pending = state.getInterruptions();
      const expected = pending.map((item) =>
        item.rawItem.type === "function_call" ? item.rawItem.callId : "",
      );
      if (!expected.length || expected.some((id) => !id || !toolResults?.[id]) ||
          Object.keys(toolResults ?? {}).some((id) => !expected.includes(id))) {
        throw new Error("Device results must match the pending tool calls");
      }
      for (const item of pending) state.approve(item);
      input = state;
    }

    const result = await createGroqRunner().run(liveDelegateAgent, input, {
      context,
      maxTurns: 6,
    });
    if (result.interruptions.length) {
      const requests = result.interruptions.map((item) => {
        if (item.rawItem.type !== "function_call") throw new Error("Unsupported tool interruption");
        const args = JSON.parse(item.rawItem.arguments || "{}") as Record<string, unknown>;
        if (item.rawItem.name === "end_session") {
          return { callId: item.rawItem.callId, name: "end_session", arguments: {} };
        }
        if (item.rawItem.name !== "manage_device_clock" || typeof args.action !== "string") {
          throw new Error("Unsupported delegated tool");
        }
        const { action, ...arguments_ } = args;
        return { callId: item.rawItem.callId, name: action, arguments: arguments_ };
      });
      reply(response, 200, {
        type: "tools",
        requests,
        continuation: sealContinuation({
          conversationId: "live",
          state: result.state.toString(),
          model: "groq",
        }),
      });
      return;
    }

    reply(response, 200, {
      type: "result",
      content: String(result.finalOutput ?? "I couldn't complete that request."),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unable to complete delegated work";
    reply(response, message.includes("too large") ? 413 : 500, { error: message });
  }
}
