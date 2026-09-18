import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import type { AgentInputItem } from "@openai/agents";
import type { ModelSelection } from "./models.js";

type Continuation = {
  conversationId: string;
  state: string;
  model?: ModelSelection;
};
type Conversation = { model: "groq"; history: AgentInputItem[] };

function key(): Buffer {
  const secret = process.env.TOOL_CONTINUATION_SECRET ??
    process.env.OPENAI_API_KEY ?? process.env.GROQ_API_KEY;
  if (!secret) throw new Error("A continuation encryption secret is required");
  return createHash("sha256").update("device-tool-continuation-v1:").update(secret).digest();
}

function seal(value: object, lifetimeMillis: number): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const ciphertext = Buffer.concat([
    cipher.update(JSON.stringify({ ...value, expires: Date.now() + lifetimeMillis })),
    cipher.final(),
  ]);
  return Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64url");
}

function open(token: string, maxLength: number): Record<string, unknown> {
  if (token.length > maxLength) throw new Error("Continuation too large");
  const data = Buffer.from(token, "base64url");
  if (data.length < 29) throw new Error("Invalid or expired continuation");
  const decipher = createDecipheriv("aes-256-gcm", key(), data.subarray(0, 12));
  decipher.setAuthTag(data.subarray(12, 28));
  const value = JSON.parse(
    Buffer.concat([decipher.update(data.subarray(28)), decipher.final()]).toString(),
  ) as Record<string, unknown>;
  if (typeof value.expires !== "number" || value.expires < Date.now()) {
    throw new Error("Invalid or expired continuation");
  }
  return value;
}

// Encrypted, authenticated state works across serverless instances; no in-memory waiter.
export function sealContinuation(value: Continuation): string {
  return seal(value, 300_000);
}

export function openContinuation(token: string): Continuation {
  const value = open(token, 4_000_000);
  const model = value.model;
  if (typeof value.state !== "string" || typeof value.conversationId !== "string" ||
      (model !== undefined && model !== "openai" && model !== "groq")) {
    throw new Error("Invalid or expired device continuation");
  }
  return {
    state: value.state,
    conversationId: value.conversationId,
    ...(model ? { model } : {}),
  };
}

export function sealConversation(value: Conversation): string {
  return `groq.${seal(value, 86_400_000)}`;
}

export function openConversation(token: string): Conversation {
  if (!token.startsWith("groq.")) {
    throw new Error("The selected model does not match this conversation");
  }
  const value = open(token.slice(5), 1_000_000);
  if (value.model !== "groq" || !Array.isArray(value.history)) {
    throw new Error("Invalid or expired conversation");
  }
  return { model: "groq", history: value.history as AgentInputItem[] };
}
