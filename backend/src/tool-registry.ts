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

export const deviceSettings = [
  "wifi",
  "mobile_data",
  "bluetooth",
  "airplane_mode",
  "brightness",
] as const;

const deviceStatusParameters = {
  type: "object",
  properties: {
    includeWifiNetworks: {
      type: "boolean",
      description:
        "Also scan and list the Wi-Fi networks in range, marking which are saved. Slower (about 3 seconds); only set when the user asks about nearby or available Wi-Fi networks.",
    },
  },
  required: [],
  additionalProperties: true,
} satisfies ToolInputParameters;

const deviceSettingParameters = {
  type: "object",
  properties: {
    setting: {
      type: "string",
      enum: [...deviceSettings],
      description: "Which phone setting to change",
    },
    enabled: {
      type: "boolean",
      description:
        "Required for wifi, mobile_data, bluetooth, and airplane_mode: true turns it on, false turns it off. Always an explicit target state, never a toggle.",
    },
    level: {
      type: "integer",
      minimum: 0,
      maximum: 100,
      description: "Required for brightness: the screen brightness as a percentage",
    },
  },
  required: ["setting"],
  additionalProperties: true,
} satisfies ToolInputParameters;

const switchWifiParameters = {
  type: "object",
  properties: {
    ssid: {
      type: "string",
      minLength: 1,
      maxLength: 64,
      description: "The exact name of a saved Wi-Fi network that is currently in range",
    },
  },
  required: ["ssid"],
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
  getDeviceStatus: {
    name: "get_device_status",
    description:
      "Read the phone's current Wi-Fi (on/off and connected network), mobile data, Bluetooth, airplane mode, and screen brightness status. Set includeWifiNetworks to also list the Wi-Fi networks in range. Use this to answer questions about these settings and before changing one when the current state matters.",
    parameters: deviceStatusParameters,
    strict: false,
  },
  setDeviceSetting: {
    name: "set_device_setting",
    description:
      "Change one phone setting. For wifi, mobile_data, bluetooth, and airplane_mode pass enabled (true = on, false = off). For brightness pass level (0-100 percent); this also turns off auto-brightness. Turning off the connection the assistant is using (Wi-Fi, mobile data, or turning on airplane mode) can end this conversation's connection, so it is applied a few seconds after the result is returned; tell the user it is happening now. Does nothing if the setting is already in the requested state.",
    parameters: deviceSettingParameters,
    strict: false,
  },
  switchWifiNetwork: {
    name: "switch_wifi_network",
    description:
      "Switch the phone to a different saved Wi-Fi network that is currently in range, by its exact name (ssid). Networks that are not already saved on the phone cannot be joined this way; the user must connect to those in Android's Wi-Fi settings because they need a password. Wi-Fi must be on. The connection drops briefly while switching.",
    parameters: switchWifiParameters,
    strict: false,
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

export const getDeviceStatus = tool<typeof deviceStatusParameters, DeviceContext>({
  ...toolDefinitions.getDeviceStatus,
  needsApproval: true,
  execute: (_input: unknown, runContext, details) => deviceResult(runContext, details),
});

export const setDeviceSetting = tool<typeof deviceSettingParameters, DeviceContext>({
  ...toolDefinitions.setDeviceSetting,
  needsApproval: true,
  execute: (_input: unknown, runContext, details) => deviceResult(runContext, details),
});

export const switchWifiNetwork = tool<typeof switchWifiParameters, DeviceContext>({
  ...toolDefinitions.switchWifiNetwork,
  needsApproval: true,
  execute: (_input: unknown, runContext, details) => deviceResult(runContext, details),
});

/** Tools the phone runs itself, besides manage_device_clock. */
const phoneSettingsToolNames: readonly string[] = [
  toolDefinitions.getDeviceStatus.name,
  toolDefinitions.setDeviceSetting.name,
  toolDefinitions.switchWifiNetwork.name,
];

export type DeviceToolRequest = { name: string; arguments: Record<string, unknown> };

/**
 * Maps a paused model tool call to the request sent to the phone. The Clock
 * tool fans out into its `action`; the phone-settings tools go by their own
 * name. Returns undefined for tools the phone does not run.
 */
export function toDeviceRequest(
  toolName: string,
  args: Record<string, unknown>,
): DeviceToolRequest | undefined {
  if (toolName === toolDefinitions.manageDeviceClock.name) {
    const { action, ...rest } = args;
    if (typeof action !== "string") throw new Error("Missing Clock action");
    return { name: action, arguments: rest };
  }
  if (phoneSettingsToolNames.includes(toolName)) return { name: toolName, arguments: args };
  return undefined;
}

// Only Groq uses this tool (OpenAI live gets the strict definition above). Groq
// rejects a strict function whose `required` list is empty, so it is sent
// non-strict instead.
const groqEndSessionParameters = {
  type: "object",
  properties: {},
  required: [],
  additionalProperties: true,
} satisfies ToolInputParameters;

export const endLiveSession = tool<typeof groqEndSessionParameters, DeviceContext>({
  ...toolDefinitions.endSession,
  parameters: groqEndSessionParameters,
  strict: false,
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
  getDeviceStatus,
  setDeviceSetting,
  switchWifiNetwork,
  webSearchTool({ searchContextSize: "low" }),
];

export const groqChatTools = [
  manageDeviceClock,
  getDeviceStatus,
  setDeviceSetting,
  switchWifiNetwork,
  searchWeb,
];

export const groqLiveTools = [
  manageDeviceClock,
  getDeviceStatus,
  setDeviceSetting,
  switchWifiNetwork,
  endLiveSession,
  searchWeb,
];

export const openAiLiveTools = [
  liveFunction(toolDefinitions.manageDeviceClock),
  liveFunction(toolDefinitions.getDeviceTime),
  liveFunction(toolDefinitions.getDeviceStatus),
  liveFunction(toolDefinitions.setDeviceSetting),
  liveFunction(toolDefinitions.switchWifiNetwork),
  liveFunction(toolDefinitions.endSession),
  { type: "web_search" as const },
];
