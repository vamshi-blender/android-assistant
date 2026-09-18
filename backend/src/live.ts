import type { IncomingMessage, ServerResponse } from "node:http";
import { authenticateApiRequest } from "./auth.js";
import { parseModelSelection } from "./models.js";

type LiveRequest = IncomingMessage & { body?: unknown };
const MAX_BODY_BYTES = 64 * 1024;

type DeviceTime = { epochMillis: number; timeZoneId: string };

const liveGreeting = {
  instructions: [
    "Greet the user immediately in English without waiting for them to speak.",
    "Give one short, energetic welcome and mention that you can chat and help with alarms and timers.",
    "Then pause and listen. Do not repeat this greeting later in the session.",
  ].join(" "),
  begin: "Begin the conversation now, following the greeting instructions provided.",
};

const clockTools = [
  {
    type: "function",
    name: "get_device_time",
    description: "Get the phone's current local date, time, day of week, and time zone.",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
    strict: true,
  },
  {
    type: "function",
    name: "set_alarm",
    description: "Set an alarm in the Android Clock app using the device's local time.",
    parameters: {
      type: "object",
      properties: {
        hour: { type: "integer", minimum: 0, maximum: 23, description: "Hour in 24-hour device-local time." },
        minute: { type: "integer", minimum: 0, maximum: 59 },
        label: { type: ["string", "null"], maxLength: 200 },
        repeatDays: {
          type: ["array", "null"],
          items: { type: "string", enum: ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"] },
          description: "Null for a one-time alarm.",
        },
        vibrate: { type: ["boolean", "null"] },
        silent: { type: ["boolean", "null"] },
      },
      required: ["hour", "minute", "label", "repeatDays", "vibrate", "silent"],
      additionalProperties: false,
    },
    strict: true,
  },
  {
    type: "function",
    name: "start_timer",
    description: "Start a countdown timer in the Android Clock app.",
    parameters: {
      type: "object",
      properties: {
        durationSeconds: { type: "integer", minimum: 1, maximum: 86_400 },
        label: { type: ["string", "null"], maxLength: 200 },
      },
      required: ["durationSeconds", "label"],
      additionalProperties: false,
    },
    strict: true,
  },
  {
    type: "function",
    name: "show_alarms",
    description: "Open the alarms page in the Android Clock app.",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
    strict: true,
  },
  {
    type: "function",
    name: "show_timers",
    description: "Open the timers page in the Android Clock app.",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
    strict: true,
  },
  {
    type: "function",
    name: "snooze_alarm",
    description: "Snooze the currently ringing Android alarm.",
    parameters: {
      type: "object",
      properties: { durationMinutes: { type: ["integer", "null"], minimum: 1, maximum: 60 } },
      required: ["durationMinutes"],
      additionalProperties: false,
    },
    strict: true,
  },
  {
    type: "function",
    name: "dismiss_alarm",
    description: "Dismiss Android alarms by next alarm, all alarms, label, or local time. This does not delete or disable them.",
    parameters: {
      type: "object",
      properties: {
        mode: { type: "string", enum: ["next", "all", "label", "time"] },
        label: { type: ["string", "null"], maxLength: 200 },
        hour: { type: ["integer", "null"], minimum: 0, maximum: 23 },
        minute: { type: ["integer", "null"], minimum: 0, maximum: 59 },
      },
      required: ["mode", "label", "hour", "minute"],
      additionalProperties: false,
    },
    strict: true,
  },
  {
    type: "function",
    name: "dismiss_expired_timers",
    description: "Dismiss all expired timers in the Android Clock app.",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
    strict: true,
  },
  {
    type: "function",
    name: "end_session",
    description: "End the live voice session after a brief spoken goodbye. Use only when the user clearly asks to end, stop, close, or hang up the session.",
    parameters: { type: "object", properties: {}, required: [], additionalProperties: false },
    strict: true,
  },
] as const;

function parseDeviceTime(body: unknown): DeviceTime | undefined {
  const value = (body as { deviceTime?: unknown } | null)?.deviceTime as
    | { epochMillis?: unknown; timeZoneId?: unknown }
    | undefined;
  if (
    typeof value?.epochMillis !== "number" ||
    !Number.isFinite(value.epochMillis) ||
    typeof value.timeZoneId !== "string" ||
    !value.timeZoneId
  ) return undefined;
  try {
    new Intl.DateTimeFormat("en", { timeZone: value.timeZoneId }).format(0);
  } catch {
    return undefined;
  }
  return { epochMillis: value.epochMillis, timeZoneId: value.timeZoneId };
}

function backendInstructions(deviceTime: DeviceTime): string {
  const currentTime = new Intl.DateTimeFormat("en-IN", {
    dateStyle: "full",
    timeStyle: "long",
    timeZone: deviceTime.timeZoneId,
  }).format(new Date(deviceTime.epochMillis));
  return [
    "You handle delegated Android Clock requests from a live voice conversation. Transcripts may contain mistakes or later corrections; use the latest context and ask for a missing essential detail instead of guessing.",
    `The authoritative device time is ${currentTime} in ${deviceTime.timeZoneId}. Use it to resolve relative alarm times.`,
    "Use get_device_time for questions about the current time, date, or day, and before resolving a relative alarm time such as tomorrow or ten minutes from now.",
    "Use the matching tool for every explicit supported request: set an alarm, start a timer, open alarms or timers, snooze an alarm, dismiss alarms, or dismiss expired timers.",
    "Use end_session once when the user clearly asks to end, stop, close, or hang up the live conversation. Do not use it when the user only asks you to stop talking or says goodbye while another task is unfinished. After it returns, provide a very brief friendly goodbye so the application can close after the speech finishes.",
    "Do not confuse dismissing with deleting or disabling. Reading, editing, deleting, enabling, disabling arbitrary Clock entries, and pausing or resuming timers are unsupported. Explain that limitation and offer to open the relevant Clock page, but only open it if the user explicitly agrees.",
    "Only report success when the device result says succeeded. Report failed, requires_user_action, and unknown accurately. Never retry an unknown action automatically because it may already have happened.",
    "Return concise verified facts and what the user should do next. Do not invent successful device actions.",
  ].join("\n\n");
}

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
  const deviceTime = parseDeviceTime(body);
  if (!deviceTime) {
    reply(response, 400, { error: "Valid device time is required" });
    return;
  }
  if (!process.env.OPENAI_API_KEY) {
    reply(response, 503, { error: "Live voice is not configured on the server" });
    return;
  }
  const modelSelection = parseModelSelection((body as { model?: unknown } | null)?.model);
  if (modelSelection === "groq" && !process.env.GROQ_API_KEY?.trim()) {
    reply(response, 503, { error: "Groq is not configured on the server" });
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
          instructions: [
            "You are an exceptionally enthusiastic, energetic, and upbeat conversational voice assistant.",
            "Speak at a brisk, fast pace with lively intonation and minimal pauses, while staying clear and easy to understand.",
            "Keep replies short, punchy, natural, and direct. Slow down slightly for important names, numbers, dates, or instructions so they remain accurate.",
            "Use moderate, upbeat backchannels without talking over the user. Stop speaking immediately when the user interrupts, then listen.",
            "You and the backend are one assistant. Never mention delegation, a backend, tools, models, transfers, or another agent. Present verified results naturally as your own answer.",
            [
              "Delegation policy:",
              "Backend tools:",
              "- Device time: read the phone's current local time, date, day, and time zone.",
              "- Android Clock: set alarms, start timers, open alarms or timers, snooze, dismiss alarms, and dismiss expired timers.",
              "- Session control: end this live conversation after a brief goodbye.",
              "Delegate to the backend when:",
              "- The user asks for the current time, date, or day.",
              "- The user requests an alarm or timer action.",
              "- A correction changes an alarm or timer request already in progress.",
              "- The user clearly asks to end, stop, close, or hang up this live conversation.",
              "Do not delegate to the backend when:",
              "- You can answer conversationally without current device information or a device action.",
              "- You need one brief clarification to understand the request.",
              "- The user asks you to stop speaking but does not ask to end the session.",
              "Delegate before giving an answer that depends on backend work. Do not guess or claim success while waiting.",
            ].join("\n"),
          ].join("\n\n"),
          audio: { output: { voice: "delta" } },
          delegation: modelSelection === "groq"
            ? { type: "client" }
            : {
                type: "responses",
                responses: {
                  model: process.env.OPENAI_LIVE_BACKEND_MODEL ?? "gpt-5.6-terra",
                  instructions: backendInstructions(deviceTime),
                  tools: clockTools,
                  tool_choice: "auto",
                  parallel_tool_calls: false,
                },
              },
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
    reply(response, 201, {
      session: { id: result.session.id },
      transport: { type: "webrtc", sdp: result.transport.sdp },
      greeting: liveGreeting,
    });
  } catch {
    reply(response, 502, { error: "Unable to connect to live voice. Please try again." });
  }
}
