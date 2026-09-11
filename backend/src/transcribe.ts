import type { IncomingMessage, ServerResponse } from "node:http";
import OpenAI, { APIError, toFile } from "openai";
import { authenticateApiRequest } from "./auth.js";

type AudioRequest = IncomingMessage & { body?: unknown };

// Leave headroom below Vercel's 4.5 MB function request-body limit.
const MAX_AUDIO_BYTES = 4 * 1024 * 1024;
const AUDIO_TOO_LARGE_MESSAGE = "Audio file exceeds the 4 MB limit";
const openai = new OpenAI();

class AudioTooLargeError extends Error {}

function mapOpenAIError(error: APIError): { status: number; message: string } {
  switch (error.status) {
    case 400:
    case 422:
      return {
        status: 400,
        message: "The recording could not be transcribed. Please record it again.",
      };
    case 401:
    case 403:
      return {
        status: 502,
        message: "The transcription service is not configured correctly.",
      };
    case 404:
      return { status: 502, message: "The transcription model is unavailable." };
    case 429:
      return { status: 429, message: "The transcription service limit was reached." };
    case 500:
    case 502:
    case 503:
    case 504:
      return {
        status: 503,
        message: "The transcription service is temporarily unavailable.",
      };
    default:
      return { status: 502, message: "The transcription service request failed." };
  }
}

function sendJson(
  response: ServerResponse,
  status: number,
  body: Record<string, unknown>,
): void {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
}

async function readAudio(request: AudioRequest): Promise<Buffer> {
  if (Buffer.isBuffer(request.body)) {
    if (request.body.length > MAX_AUDIO_BYTES) {
      throw new AudioTooLargeError();
    }
    return request.body;
  }
  if (request.body instanceof Uint8Array) {
    if (request.body.byteLength > MAX_AUDIO_BYTES) {
      throw new AudioTooLargeError();
    }
    return Buffer.from(request.body);
  }

  const chunks: Buffer[] = [];
  let totalBytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    totalBytes += buffer.length;
    if (totalBytes > MAX_AUDIO_BYTES) throw new AudioTooLargeError();
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}

export async function handleTranscription(
  request: AudioRequest,
  response: ServerResponse,
): Promise<void> {
  if (request.method !== "POST") {
    sendJson(response, 405, { error: "Method not allowed" });
    return;
  }

  if (!authenticateApiRequest(request, response)) return;

  const contentType = request.headers["content-type"]?.split(";", 1)[0]?.trim();
  if (contentType !== "audio/mp4" && contentType !== "audio/m4a") {
    sendJson(response, 415, { error: "Content-Type must be audio/mp4 or audio/m4a" });
    return;
  }

  const declaredLength = Number(request.headers["content-length"] ?? 0);
  if (declaredLength > MAX_AUDIO_BYTES) {
    sendJson(response, 413, { error: AUDIO_TOO_LARGE_MESSAGE });
    return;
  }

  try {
    const audio = await readAudio(request);
    if (audio.length === 0) {
      sendJson(response, 400, { error: "Audio recording is required" });
      return;
    }

    const file = await toFile(audio, "recording.m4a", { type: contentType });
    const transcription = await openai.audio.transcriptions.create({
      file,
      model: "gpt-transcribe",
    });

    sendJson(response, 200, { text: transcription.text });
  } catch (error) {
    if (error instanceof AudioTooLargeError) {
      sendJson(response, 413, { error: AUDIO_TOO_LARGE_MESSAGE });
      return;
    }

    if (error instanceof APIError) {
      console.error("OpenAI transcription request failed", {
        status: error.status,
        code: error.code,
        requestId: error.requestID,
      });
      const mapped = mapOpenAIError(error);
      sendJson(response, mapped.status, { error: mapped.message });
      return;
    }

    console.error("Unexpected transcription failure", error);
    sendJson(response, 500, { error: "Unable to transcribe the recording." });
  }
}
