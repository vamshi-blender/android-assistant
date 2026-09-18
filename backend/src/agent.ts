import { Agent, tool, type ToolInputParameters } from "@openai/agents";
import { z } from "zod";

export type DeviceContext = {
  deviceTime: {
    epochMillis: number;
    timeZoneId: string;
    receivedAtServerEpochMillis: number;
  };
  toolResults?: Record<string, DeviceToolResult>;
};

const setAlarmParameters = z.object({
  hour: z.number().int().min(0).max(23).describe("Hour in 24-hour local time"),
  minute: z.number().int().min(0).max(59),
  label: z.string().max(200).optional(),
  repeatDays: z
    .array(
      z.enum([
        "sunday",
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
      ]),
    )
    .optional()
    .describe("Omit for a one-time alarm"),
  vibrate: z.boolean().optional(),
  silent: z.boolean().optional(),
});
const startTimerParameters = z.object({
  durationSeconds: z.number().int().min(1).max(86_400),
  label: z.string().max(200).optional(),
});
const snoozeAlarmParameters = z.object({
  durationMinutes: z.number().int().min(1).max(60).optional(),
});
const dismissAlarmParameters = z.object({
  mode: z.enum(["next", "all", "label", "time"]),
  label: z.string().max(200).optional(),
  hour: z.number().int().min(0).max(23).optional(),
  minute: z.number().int().min(0).max(59).optional(),
});
const clockActionParameters = z.object({
  action: z
    .enum([
      "set_alarm",
      "start_timer",
      "show_alarms",
      "show_timers",
      "snooze_alarm",
      "dismiss_alarm",
      "dismiss_expired_timers",
    ])
    .describe("The Clock operation to perform"),
  hour: z.number().int().min(0).max(23).optional(),
  minute: z.number().int().min(0).max(59).optional(),
  label: z.string().max(200).optional(),
  repeatDays: setAlarmParameters.shape.repeatDays,
  vibrate: z.boolean().optional(),
  silent: z.boolean().optional(),
  durationSeconds: z.number().int().min(1).max(86_400).optional(),
  durationMinutes: z.number().int().min(1).max(60).optional(),
  mode: dismissAlarmParameters.shape.mode.optional(),
});

const clockActionParametersJson = {
  type: "object",
  properties: {
    action: {
      type: "string",
      enum: [
        "set_alarm", "start_timer", "show_alarms", "show_timers",
        "snooze_alarm", "dismiss_alarm", "dismiss_expired_timers",
      ],
      description: "The Clock operation to perform",
    },
    hour: { type: "integer", minimum: 0, maximum: 23 },
    minute: { type: "integer", minimum: 0, maximum: 59 },
    label: { type: "string", maxLength: 200 },
    repeatDays: {
      type: "array",
      items: {
        type: "string",
        enum: [
          "sunday", "monday", "tuesday", "wednesday",
          "thursday", "friday", "saturday",
        ],
      },
    },
    vibrate: { type: "boolean" },
    silent: { type: "boolean" },
    durationSeconds: { type: "integer", minimum: 1, maximum: 86_400 },
    durationMinutes: { type: "integer", minimum: 1, maximum: 60 },
    mode: { type: "string", enum: ["next", "all", "label", "time"] },
  },
  required: ["action"],
  additionalProperties: true,
} satisfies ToolInputParameters;

export type DeviceToolResult = {
  status: "succeeded" | "failed" | "unknown" | "requires_user_action";
  message: string;
};

export const manageDeviceClock = tool<typeof clockActionParametersJson, DeviceContext>({
  name: "manage_device_clock",
  description:
    "Perform one supported action in Android's default Clock app. Actions: set_alarm needs hour/minute and may include label, repeatDays, silent, and vibrate; start_timer needs durationSeconds and may include label; show_alarms and show_timers open their Clock pages; snooze_alarm may include durationMinutes; dismiss_alarm needs mode (next, all, label, or time), plus label for label mode or hour/minute for time mode; dismiss_expired_timers dismisses all expired timers. Dismissing is supported and is distinct from deleting or explicitly disabling an entry.",
  parameters: clockActionParametersJson,
  strict: false,
  needsApproval: true, // Pause the run until Android returns the actual result.
  execute: (input, runContext, details) => {
    const typedInput = input as z.infer<typeof clockActionParameters>;
    const { action } = typedInput;
    if (action === "set_alarm" && (typedInput.hour === undefined || typedInput.minute === undefined)) {
      return JSON.stringify({ status: "failed", error: "hour and minute are required" });
    }
    if (action === "start_timer" && typedInput.durationSeconds === undefined) {
      return JSON.stringify({ status: "failed", error: "durationSeconds is required" });
    }
    if (action === "dismiss_alarm" && typedInput.mode === undefined) {
      return JSON.stringify({ status: "failed", error: "mode is required" });
    }
    if (action === "dismiss_alarm" && typedInput.mode === "label" && !typedInput.label) {
      return JSON.stringify({ status: "failed", error: "label is required for label mode" });
    }
    if (action === "dismiss_alarm" && typedInput.mode === "time" && typedInput.hour === undefined) {
      return JSON.stringify({ status: "failed", error: "hour is required for time mode" });
    }
    const callId = details?.toolCall?.callId;
    const result = callId ? runContext?.context.toolResults?.[callId] : undefined;
    return JSON.stringify(result ?? { status: "unknown", message: "No device result was received. Do not retry automatically." });
  },
});

const endSessionParameters = z.object({
  reason: z.string().max(200).describe("The user's explicit request to end the live session"),
});
export const endLiveSession = tool<typeof endSessionParameters, DeviceContext>({
  name: "end_session",
  description:
    "End the live voice session after a brief spoken goodbye. Use only when the user clearly asks to end, stop, close, or hang up the live conversation.",
  parameters: endSessionParameters,
  needsApproval: true,
  execute: (_input, runContext, details) => {
    const callId = details?.toolCall?.callId;
    const result = callId ? runContext?.context.toolResults?.[callId] : undefined;
    return JSON.stringify(result ?? {
      status: "unknown",
      message: "No device result was received. Do not retry automatically.",
    });
  },
});

export const assistantAgent = new Agent<DeviceContext>({
  name: "Mobile assistant",
  instructions:
    "You are a helpful mobile AI assistant. Be accurate, friendly, and concise. Every user message is preceded by a <current_time> tag giving the user's device time, converted to India (Asia/Kolkata) — treat it as authoritative for resolving relative dates or times, and never ask the user what time it is. Use manage_device_clock for every supported alarm or timer request: setting alarms, starting timers, opening alarms or timers, snoozing, dismissing alarms (next, all, by label, or by time), and dismissing expired timers. Always perform an explicitly requested supported action. Do not confuse dismissing with deleting or explicitly disabling: dismiss_alarm is supported. Android does not expose portable APIs to read Clock entries into chat, edit or delete them, explicitly enable/disable arbitrary entries, or pause/resume timers. For an unsupported request, explain the limitation and offer to open the relevant Clock page, but do not open it unless the user explicitly asks you to. Only claim success when the device tool result status is succeeded. Report failed, unknown, or requires_user_action results accurately. Never automatically retry an unknown Clock action because it may already have happened. Clock changes require this app to be the default Android assistant and a Clock app supporting voice interaction. Before a tool call, send a brief commentary progress update. Use commentary only for progress and put the completed response in the final answer phase.",
  model: process.env.OPENAI_MODEL ?? "gpt-5.6",
  tools: [manageDeviceClock],
});

// Keep provider-specific settings separate so GPT-5 defaults such as `verbosity`
// are never forwarded to Groq's Chat Completions endpoint.
export const groqAssistantAgent = new Agent<DeviceContext>({
  name: "Mobile assistant",
  instructions: assistantAgent.instructions,
  model: "openai/gpt-oss-20b",
  modelSettings: {},
  tools: [manageDeviceClock],
});
