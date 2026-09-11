import { timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

const API_KEY_HEADER = "x-api-key";

function sendError(response: ServerResponse, status: number, error: string): void {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify({ error }));
}

export function authenticateApiRequest(
  request: IncomingMessage,
  response: ServerResponse,
): boolean {
  const expected = process.env.APP_API_KEY;
  if (!expected) {
    sendError(response, 503, "Backend authentication is not configured");
    return false;
  }

  const suppliedHeader = request.headers[API_KEY_HEADER];
  const supplied = Array.isArray(suppliedHeader) ? suppliedHeader[0] : suppliedHeader;
  if (!supplied) {
    sendError(response, 401, "API key is required");
    return false;
  }

  const expectedBytes = Buffer.from(expected, "utf8");
  const suppliedBytes = Buffer.from(supplied, "utf8");
  if (
    expectedBytes.length !== suppliedBytes.length ||
    !timingSafeEqual(expectedBytes, suppliedBytes)
  ) {
    sendError(response, 401, "Invalid API key");
    return false;
  }

  return true;
}
