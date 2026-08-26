import { Agent, tool } from "@openai/agents";
import { z } from "zod";

export type DeviceTimeContext = {
  deviceTime: {
    epochMillis: number;
    timeZoneId: string;
    receivedAtServerEpochMillis: number;
  };
};

const getDeviceTime = tool<typeof emptyParameters, DeviceTimeContext>({
  name: "get_device_time",
  description:
    "Get the current date and time from the user's Android device. Use this whenever the user asks for their current time or date.",
  parameters: z.object({}),
  execute: (_input, runContext) => {
    const deviceTime = runContext?.context.deviceTime;
    if (!deviceTime) return "Device time is unavailable.";

    // Preserve any manual device-clock offset while accounting for time spent in this run.
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

const emptyParameters = z.object({});

export const assistantAgent = new Agent<DeviceTimeContext>({
  name: "Mobile assistant",
  instructions:
    "You are a helpful mobile AI assistant. Be accurate, friendly, and concise. Always call get_device_time when asked for the current time or date; never infer it yourself.",
  model: process.env.OPENAI_MODEL ?? "gpt-5.6",
  tools: [getDeviceTime],
});
