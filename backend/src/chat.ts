import {
  isOpenAIResponsesRawModelStreamEvent,
  run,
  type RunToolCallItem,
  type RunToolCallOutputItem,
} from "@openai/agents";
import OpenAI from "openai";
import { assistantAgent, type DeviceContext } from "./agent.js";

export type ChatStreamEvent =
  | { type: "conversation.ready"; conversationId: string }
  | {
      type: "assistant.text.delta";
      delta: string;
      messageId: string;
      phase: "commentary" | "final_answer";
      startsNewTextSegment?: true;
    }
  | {
      type: "tool.execution.started";
      callId: string;
      name: string;
      arguments: Record<string, unknown>;
      startedAt: number;
    }
  | {
      type: "tool.execution.completed";
      callId: string;
      output?: string;
      completedAt: number;
    }
  | {
      type: "client.tool.requested";
      name: string;
      arguments: Record<string, unknown>;
    }
  | { type: "response.completed" }
  | { type: "response.error"; message: string };

type AssistantOutputPhase = "commentary" | "final_answer";

interface AssistantTextSegment {
  messageId: string;
  phase: AssistantOutputPhase;
}

function assistantOutputPhase(value: unknown): AssistantOutputPhase {
  return value === "commentary" ? "commentary" : "final_answer";
}

function parseArguments(value: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

function toolExecutionStartedEvent(
  item: RunToolCallItem,
): ChatStreamEvent | null {
  if (!item.callId || !item.toolName) return null;

  return {
    type: "tool.execution.started",
    callId: item.callId,
    name: item.toolName,
    arguments:
      item.rawItem.type === "function_call"
        ? parseArguments(item.rawItem.arguments)
        : {},
    startedAt: Date.now(),
  };
}

function toolOutputPreview(item: RunToolCallOutputItem): string | undefined {
  try {
    const output =
      typeof item.output === "string" ? item.output : JSON.stringify(item.output);
    if (!output) return undefined;
    return output.length > 1_200 ? `${output.slice(0, 1_200)}…` : output;
  } catch {
    return undefined;
  }
}

const openai = new OpenAI();

export async function streamAssistantResponse(
  userMessage: string,
  existingConversationId: string | undefined,
  context: Omit<DeviceContext, "emitClientToolRequest">,
  emit: (event: ChatStreamEvent) => void,
): Promise<void> {
  const conversationId =
    existingConversationId ?? (await openai.conversations.create()).id;

  emit({ type: "conversation.ready", conversationId });

  const deviceContext: DeviceContext = {
    ...context,
    emitClientToolRequest: (
      name: string,
      arguments_: Record<string, unknown>,
    ) => emit({ type: "client.tool.requested", name, arguments: arguments_ }),
  };

  const stream = await run<typeof assistantAgent, DeviceContext>(
    assistantAgent,
    userMessage,
    {
      conversationId,
      context: deviceContext,
      stream: true,
    },
  );

  let startsNewTextSegment = true;
  let fallbackSegmentNumber = 0;
  let currentSegment: AssistantTextSegment = {
    messageId: `assistant-${fallbackSegmentNumber}`,
    phase: "final_answer",
  };
  const segmentsByOutputIndex = new Map<number, AssistantTextSegment>();

  for await (const event of stream) {
    if (isOpenAIResponsesRawModelStreamEvent(event)) {
      const modelEvent = event.data.event;

      if (modelEvent.type === "response.created") {
        segmentsByOutputIndex.clear();
      } else if (
        modelEvent.type === "response.output_item.added" &&
        modelEvent.item.type === "message"
      ) {
        const segment: AssistantTextSegment = {
          messageId:
            modelEvent.item.id || `assistant-${++fallbackSegmentNumber}`,
          phase: assistantOutputPhase(modelEvent.item.phase),
        };
        segmentsByOutputIndex.set(modelEvent.output_index, segment);
        currentSegment = segment;
        startsNewTextSegment = true;
      }

      continue;
    }

    if (
      event.type === "run_item_stream_event" &&
      event.name === "message_output_created"
    ) {
      startsNewTextSegment = true;
      continue;
    }

    if (
      event.type === "run_item_stream_event" &&
      event.name === "tool_called" &&
      event.item.type === "tool_call_item"
    ) {
      const toolEvent = toolExecutionStartedEvent(event.item);
      if (toolEvent) emit(toolEvent);
      continue;
    }

    if (
      event.type === "run_item_stream_event" &&
      event.name === "tool_output" &&
      event.item.type === "tool_call_output_item"
    ) {
      if (event.item.callId) {
        const output = toolOutputPreview(event.item);
        emit({
          type: "tool.execution.completed",
          callId: event.item.callId,
          ...(output ? { output } : {}),
          completedAt: Date.now(),
        });
      }
      continue;
    }

    if (
      event.type === "raw_model_stream_event" &&
      event.data.type === "output_text_delta"
    ) {
      if (!event.data.delta) continue;
      const outputIndex = event.data.providerData?.output_index;
      const segment =
        typeof outputIndex === "number"
          ? segmentsByOutputIndex.get(outputIndex) ?? currentSegment
          : currentSegment;

      emit({
        type: "assistant.text.delta",
        delta: event.data.delta,
        messageId: segment.messageId,
        phase: segment.phase,
        ...(startsNewTextSegment ? { startsNewTextSegment: true } : {}),
      });
      startsNewTextSegment = false;
    }
  }

  await stream.completed;
  emit({ type: "response.completed" });
}
