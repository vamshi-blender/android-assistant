import { Agent, tool } from "@openai/agents";
import { z } from "zod";

export type DeviceContext = {
  deviceTime: {
    epochMillis: number;
    timeZoneId: string;
    receivedAtServerEpochMillis: number;
  };
  emitClientToolRequest: (
    name: string,
    arguments_: Record<string, unknown>,
  ) => void;
};

const emptyParameters = z.object({});
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

function dispatchDeviceAction(
  context: DeviceContext | undefined,
  name: string,
  arguments_: Record<string, unknown>,
): string {
  if (!context) {
    return JSON.stringify({ status: "failed", error: "Device context is unavailable." });
  }

  context.emitClientToolRequest(name, arguments_);
  return JSON.stringify({
    status: "dispatched_to_android",
    action: name,
    note: "The Android device will pass this request to its default Clock app.",
  });
}

const getDeviceTime = tool<typeof emptyParameters, DeviceContext>({
  name: "get_device_time",
  description:
    "Get the current date and time from the user's Android device. Use this for current time/date and when resolving relative alarm requests.",
  parameters: emptyParameters,
  execute: (_input, runContext) => {
    const deviceTime = runContext?.context.deviceTime;
    if (!deviceTime) return "Device time is unavailable.";

    const elapsedMillis = Math.max(
      0,
      Date.now() - deviceTime.receivedAtServerEpochMillis,
    );
    const currentDeviceEpochMillis = deviceTime.epochMillis + elapsedMillis;

    try {
      const formatted = new Intl.DateTimeFormat("en-US", {
        dateStyle: "full",
        timeStyle: "long",
        timeZone: deviceTime.timeZoneId,
      }).format(new Date(currentDeviceEpochMillis));
      return JSON.stringify({
        currentDeviceTime: formatted,
        timeZone: deviceTime.timeZoneId,
        epochMillis: currentDeviceEpochMillis,
        source: "android_device_clock",
      });
    } catch {
      return JSON.stringify({
        currentDeviceTime: new Date(currentDeviceEpochMillis).toISOString(),
        timeZone: deviceTime.timeZoneId,
        epochMillis: currentDeviceEpochMillis,
        source: "android_device_clock",
      });
    }
  },
});

const manageDeviceClock = tool<typeof clockActionParameters, DeviceContext>({
  name: "manage_device_clock",
  description:
    "Perform one supported action in Android's default Clock app. Actions: set_alarm needs hour/minute and may include label, repeatDays, silent, and vibrate; start_timer needs durationSeconds and may include label; show_alarms and show_timers open their Clock pages; snooze_alarm may include durationMinutes; dismiss_alarm needs mode (next, all, label, or time), plus label for label mode or hour/minute for time mode; dismiss_expired_timers dismisses all expired timers. Dismissing is supported and is distinct from deleting or explicitly disabling an entry.",
  parameters: clockActionParameters,
  execute: (input, runContext) => {
    const { action, ...arguments_ } = input;
    if (action === "set_alarm" && (input.hour === undefined || input.minute === undefined)) {
      return JSON.stringify({ status: "failed", error: "hour and minute are required" });
    }
    if (action === "start_timer" && input.durationSeconds === undefined) {
      return JSON.stringify({ status: "failed", error: "durationSeconds is required" });
    }
    if (action === "dismiss_alarm" && input.mode === undefined) {
      return JSON.stringify({ status: "failed", error: "mode is required" });
    }
    if (action === "dismiss_alarm" && input.mode === "label" && !input.label) {
      return JSON.stringify({ status: "failed", error: "label is required for label mode" });
    }
    if (action === "dismiss_alarm" && input.mode === "time" && input.hour === undefined) {
      return JSON.stringify({ status: "failed", error: "hour is required for time mode" });
    }
    return dispatchDeviceAction(runContext?.context, action, arguments_);
  },
});

export const assistantAgent = new Agent<DeviceContext>({
  name: "Mobile assistant",
  instructions:
    "You are a helpful mobile AI assistant. Be accurate, friendly, and concise. Call get_device_time before resolving relative dates or times. Use manage_device_clock for every supported alarm or timer request: setting alarms, starting timers, opening alarms or timers, snoozing, dismissing alarms (next, all, by label, or by time), and dismissing expired timers. Always perform an explicitly requested supported action. Do not confuse dismissing with deleting or explicitly disabling: dismiss_alarm is supported. Android does not expose portable APIs to read Clock entries into chat, edit or delete them, explicitly enable/disable arbitrary entries, or pause/resume timers. For an unsupported request, explain the limitation and offer to open the relevant Clock page, but do not open it unless the user explicitly asks you to. Before a tool call, send a brief commentary progress update. Use commentary only for progress and put the completed response in the final answer phase.",
  model: process.env.OPENAI_MODEL ?? "gpt-5.6",
  tools: [getDeviceTime, manageDeviceClock],
});
