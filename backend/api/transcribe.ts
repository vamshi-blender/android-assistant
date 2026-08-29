import type { IncomingMessage, ServerResponse } from "node:http";
import { handleTranscription } from "../src/transcribe.js";

export default async function handler(
  request: IncomingMessage & { body?: unknown },
  response: ServerResponse,
): Promise<void> {
  await handleTranscription(request, response);
}
