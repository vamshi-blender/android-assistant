import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

type Continuation = { conversationId: string; state: string };
function key(): Buffer {
  const secret = process.env.TOOL_CONTINUATION_SECRET ?? process.env.OPENAI_API_KEY;
  if (!secret) throw new Error("A continuation encryption secret is required");
  return createHash("sha256").update("device-tool-continuation-v1:").update(secret).digest();
}
// Encrypted, authenticated state works across serverless instances; no in-memory waiter.
export function sealContinuation(value: Continuation): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify({ ...value, expires: Date.now() + 300_000 })), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64url");
}
export function openContinuation(token: string): Continuation {
  if (token.length > 4_000_000) throw new Error("Continuation too large");
  const data = Buffer.from(token, "base64url");
  const decipher = createDecipheriv("aes-256-gcm", key(), data.subarray(0, 12));
  decipher.setAuthTag(data.subarray(12, 28));
  const value = JSON.parse(Buffer.concat([decipher.update(data.subarray(28)), decipher.final()]).toString());
  if (typeof value.expires !== "number" || value.expires < Date.now() ||
      typeof value.state !== "string" || typeof value.conversationId !== "string") {
    throw new Error("Invalid or expired device continuation");
  }
  return value;
}
