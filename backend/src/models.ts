import { OpenAIProvider, Runner } from "@openai/agents";

export const GROQ_MODEL = "openai/gpt-oss-20b";
export type ModelSelection = "openai" | "groq";

export function parseModelSelection(value: unknown): ModelSelection {
  return value === "groq" ? "groq" : "openai";
}

export function createGroqRunner(): Runner {
  const apiKey = process.env.GROQ_API_KEY?.trim();
  if (!apiKey) throw new Error("Groq is not configured on the server");

  return new Runner({
    model: GROQ_MODEL,
    modelProvider: new OpenAIProvider({
      apiKey,
      baseURL: "https://api.groq.com/openai/v1",
      useResponses: false,
    }),
    modelSettings: {
      parallelToolCalls: false,
      reasoning: { effort: "low" },
    },
    tracingDisabled: true,
  });
}
