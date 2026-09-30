import { tool, webSearchTool, type ToolInputParameters } from "@openai/agents";
import { searchWeb } from "./web-search.js";

export type DeviceToolResult = {
  status: "succeeded" | "failed" | "unknown" | "requires_user_action";
  message: string;
};

export type DeviceContext = {
  deviceTime: {
    epochMillis: number;
    timeZoneId: string;
    receivedAtServerEpochMillis: number;
  };
  toolResults?: Record<string, DeviceToolResult>;
};

export const clockActions = [
  "set_alarm",
  "start_timer",
  "show_alarms",
  "show_timers",
  "snooze_alarm",
  "dismiss_alarm",
  "dismiss_expired_timers",
] as const;

type ClockAction = (typeof clockActions)[number];
type ClockToolInput = {
  action: ClockAction;
  hour?: number;
  minute?: number;
  label?: string;
  repeatDays?: string[];
  vibrate?: boolean;
  silent?: boolean;
  durationSeconds?: number;
  durationMinutes?: number;
  mode?: "next" | "all" | "label" | "time";
};

const clockActionParameters = {
  type: "object",
  properties: {
    action: {
      type: "string",
      enum: [...clockActions],
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

const emptyParameters = {
  type: "object",
  properties: {},
  required: [],
  additionalProperties: false,
} satisfies ToolInputParameters;

const toolDefinitions = {
  manageDeviceClock: {
    name: "manage_device_clock",
    description:
      "Perform one supported action in Android's default Clock app. Actions: set_alarm needs hour/minute and may include label, repeatDays, silent, and vibrate; start_timer needs durationSeconds and may include label; show_alarms and show_timers open their Clock pages; snooze_alarm may include durationMinutes; dismiss_alarm needs mode (next, all, label, or time), plus label for label mode or hour/minute for time mode; dismiss_expired_timers dismisses all expired timers. Dismissing is supported and is distinct from deleting or explicitly disabling an entry.",
    parameters: clockActionParameters,
    strict: false,
  },
  getDeviceTime: {
    name: "get_device_time",
    description: "Get the phone's current local date, time, day of week, and time zone.",
    parameters: emptyParameters,
    strict: true,
  },
  endSession: {
    name: "end_session",
    description:
      "End the live voice session after a brief spoken goodbye. Use only when the user clearly asks to end, stop, close, or hang up the live conversation.",
    parameters: emptyParameters,
    strict: true,
  },
} as const;

function deviceResult(
  runContext: { context: DeviceContext } | undefined,
  details: { toolCall?: { callId?: string } } | undefined,
): string {
  const callId = details?.toolCall?.callId;
  const result = callId ? runContext?.context.toolResults?.[callId] : undefined;
  return JSON.stringify(result ?? {
    status: "unknown",
    message: "No device result was received. Do not retry automatically.",
  });
}

export const manageDeviceClock = tool<typeof clockActionParameters, DeviceContext>({
  ...toolDefinitions.manageDeviceClock,
  needsApproval: true,
  execute: (input: unknown, runContext, details) => {
    const typedInput = input as ClockToolInput;
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
    return deviceResult(runContext, details);
  },
});

export const endLiveSession = tool<typeof emptyParameters, DeviceContext>({
  ...toolDefinitions.endSession,
  needsApproval: true,
  execute: (_input, runContext, details) => deviceResult(runContext, details),
});

function liveFunction<T extends {
  name: string;
  description: string;
  parameters: ToolInputParameters;
  strict: boolean;
}>(definition: T) {
  return { type: "function" as const, ...definition };
}

// Tool availability is selected here. Definitions shared by multiple agents
// remain single-source, while agent-only tools stay out of unrelated contexts.
export const openAiChatTools = [
  manageDeviceClock,
  webSearchTool({ searchContextSize: "low" }),
];

export const groqChatTools = [manageDeviceClock, searchWeb];

export const groqLiveTools = [manageDeviceClock, endLiveSession, searchWeb];

export const openAiLiveTools = [
  liveFunction(toolDefinitions.manageDeviceClock),
  liveFunction(toolDefinitions.getDeviceTime),
  liveFunction(toolDefinitions.endSession),
  { type: "web_search" as const },
];
