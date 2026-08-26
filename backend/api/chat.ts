import type { IncomingMessage, ServerResponse } from "node:http";
import { handleChat } from "../src/http.js";

export default async function handler(
  request: IncomingMessage & { body?: unknown },
  response: ServerResponse,
): Promise<void> {
  await handleChat(request, response);
}
