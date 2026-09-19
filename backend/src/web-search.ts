import { tool } from "@openai/agents";
import OpenAI from "openai";
import { z } from "zod";

const parameters = z.object({
  query: z.string().min(2).max(500).describe("A focused web search query"),
});

/**
 * Groq's Chat Completions adapter cannot run OpenAI-hosted tools directly, so
 * this function gives Groq agents the same OpenAI Responses web-search backend.
 */
export const searchWeb = tool({
  name: "search_web",
  description:
    "Search the live web for current or explicitly requested information. Returns a concise grounded answer with source links.",
  parameters,
  async execute({ query }) {
    const client = new OpenAI();
    const response = await client.responses.create({
      model:
        process.env.OPENAI_WEB_SEARCH_MODEL ??
        process.env.OPENAI_LIVE_BACKEND_MODEL ??
        "gpt-5.6-terra",
      instructions:
        "Search the web for the requested information. Give a concise factual answer and cite the most relevant sources.",
      tools: [{ type: "web_search", search_context_size: "low" }],
      input: query,
    });

    if (!response.output_text.trim()) {
      return "No useful web search result was found.";
    }
    return response.output_text;
  },
});
