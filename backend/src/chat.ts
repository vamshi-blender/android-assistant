import {
  isOpenAIResponsesRawModelStreamEvent,
  run,
  RunState,
  RunContext,
  type RunToolCallOutputItem,
  type AgentInputItem,
} from "@openai/agents";
import OpenAI from "openai";
import {
  sealContinuation,
  openContinuation,
  sealConversation,
  openConversation,
} from "./continuation.js";
import { assistantAgent, groqAssistantAgent, type DeviceContext } from "./agent.js";
import { createGroqRunner, type ModelSelection } from "./models.js";

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
      type: "client.tools.requested";
      continuation: string;
      requests: { callId: string; name: string; arguments: Record<string, unknown> }[];
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

const KOLKATA_TIME_ZONE = "Asia/Kolkata";

function currentKolkataTimeTag(deviceTime: DeviceContext["deviceTime"]): string {
  const elapsedMillis = Math.max(0, Date.now() - deviceTime.receivedAtServerEpochMillis);
  const currentEpochMillis = deviceTime.epochMillis + elapsedMillis;
  const formatted = new Intl.DateTimeFormat("en-IN", {
    dateStyle: "full",
    timeStyle: "long",
    timeZone: KOLKATA_TIME_ZONE,
  }).format(new Date(currentEpochMillis));

  return `<current_time>Device time (Asia/Kolkata): ${formatted}</current_time>`;
}

export async function streamAssistantResponse(
  userMessage: string,
  existingConversationId: string | undefined,
  context: DeviceContext,
  emit: (event: ChatStreamEvent) => void,
  continuation?: string,
  modelSelection: ModelSelection = "openai",
): Promise<void> {
  const saved = continuation ? openContinuation(continuation) : undefined;
  const selectedModel = saved?.model ?? modelSelection;
  const activeAgent = selectedModel === "groq" ? groqAssistantAgent : assistantAgent;
  if (saved?.model && saved.model !== modelSelection) {
    throw new Error("The continuation model does not match the selected model");
  }
  if (selectedModel === "openai" && existingConversationId?.startsWith("groq.")) {
    throw new Error("The selected model does not match this conversation");
  }
  const conversationId = selectedModel === "openai"
    ? saved?.conversationId ?? existingConversationId ?? (await openai.conversations.create()).id
    : saved?.conversationId ?? existingConversationId ?? "";
  if (selectedModel === "openai") emit({ type: "conversation.ready", conversationId });
  const deviceContext: DeviceContext = { ...context };
  const turnInput = `${currentKolkataTimeTag(context.deviceTime)}\n\n${userMessage}`;
  let input: string | AgentInputItem[] | RunState<DeviceContext, typeof activeAgent> =
    selectedModel === "groq"
      ? [
          ...(conversationId ? openConversation(conversationId).history : []),
          { role: "user", content: turnInput },
        ]
      : turnInput;
  if (saved) {
    const state = await RunState.fromStringWithContext(
      activeAgent, saved.state, new RunContext(deviceContext), { contextStrategy: "replace" },
    );
    const pending = state.getInterruptions();
    const expected = pending.map(item => item.rawItem.type === "function_call" ? item.rawItem.callId : "");
    if (!expected.length || expected.some(id => !id || !context.toolResults?.[id]) ||
        Object.keys(context.toolResults ?? {}).some(id => !expected.includes(id))) {
      throw new Error("Device results must match the pending tool calls");
    }
    for (const item of pending) state.approve(item);
    input = state;
  }
  const stream = selectedModel === "groq"
    ? await createGroqRunner().run(groqAssistantAgent, input, {
        context: deviceContext,
        stream: true,
      })
    : await run(assistantAgent, input, {
        conversationId,
        context: deviceContext,
        stream: true,
      });

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
  if (stream.interruptions.length) {
    const requests = stream.interruptions.map(item => {
      if (item.rawItem.type !== "function_call" || item.rawItem.name !== "manage_device_clock") {
        throw new Error("Unsupported device tool interruption");
      }
      const { action, ...arguments_ } = parseArguments(item.rawItem.arguments);
      if (typeof action !== "string") throw new Error("Missing Clock action");
      emit({ type: "tool.execution.started", callId: item.rawItem.callId,
        name: item.rawItem.name, arguments: { action, ...arguments_ }, startedAt: Date.now() });
      return { callId: item.rawItem.callId, name: action, arguments: arguments_ };
    });
    emit({ type: "client.tools.requested", requests,
      continuation: sealContinuation({
        conversationId,
        state: stream.state.toString(),
        model: selectedModel,
      }) });
    return;
  }
  if (selectedModel === "groq") {
    emit({
      type: "conversation.ready",
      conversationId: sealConversation({ model: "groq", history: stream.history }),
    });
  }
  emit({ type: "response.completed" });
}
