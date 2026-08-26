# AI Assistant backend

## Local setup

1. Copy `.env.example` to `.env` and set `OPENAI_API_KEY`.
2. Install packages with `npm install`.
3. Start the server with `npm run dev`.

The server listens on `http://localhost:3000`. Before running the Android app,
forward that port to an attached emulator or USB device:

```bash
adb reverse tcp:3000 tcp:3000
```

The app then reaches the backend through `http://localhost:3000`.

Test the SSE endpoint directly:

```bash
curl -N -X POST http://localhost:3000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello"}'
```

Send the returned `conversationId` with later messages to continue the same chat.

## Vercel

Create a Vercel project with this `backend` folder as its root directory, add
`OPENAI_API_KEY` and optionally `OPENAI_MODEL`, then deploy. Update `CHAT_URL` in
the Android `ChatApi.kt` file to the resulting HTTPS endpoint.
