import { run } from "@openai/agents";
import OpenAI from "openai";
import { assistantAgent, type DeviceTimeContext } from "./agent.js";

export type ChatEvent =
  | { type: "session"; conversationId: string }
  | { type: "delta"; text: string }
  | { type: "done" }
  | { type: "error"; message: string };

const openai = new OpenAI();

export async function streamChat(
  message: string,
  existingConversationId: string | undefined,
  context: DeviceTimeContext,
  emit: (event: ChatEvent) => void,
): Promise<void> {
  const conversationId =
    existingConversationId ?? (await openai.conversations.create()).id;

  emit({ type: "session", conversationId });

  const stream = await run(assistantAgent, message, {
    conversationId,
    context,
    stream: true,
  });

  for await (const event of stream) {
    if (
      event.type === "raw_model_stream_event" &&
      event.data.type === "output_text_delta"
    ) {
      emit({ type: "delta", text: event.data.delta });
    }
  }

  await stream.completed;
  emit({ type: "done" });
}
