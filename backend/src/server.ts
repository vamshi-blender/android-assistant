import { createServer } from "node:http";
import { handleChat } from "./http.js";
import { handleTranscription } from "./transcribe.js";

const port = Number(process.env.PORT ?? 3000);

createServer(async (request, response) => {
  if (request.url === "/api/chat") {
    await handleChat(request, response);
    return;
  }

  if (request.url === "/api/transcribe") {
    await handleTranscription(request, response);
    return;
  }

  response.writeHead(404, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ error: "Not found" }));
}).listen(port, () => {
  console.log(`AI backend listening on http://localhost:${port}`);
});
