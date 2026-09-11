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
Run the command again whenever the Android device reconnects or reboots.

Test the SSE endpoint directly:

```bash
curl -N -X POST http://localhost:3000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello"}'
```

Send the returned `conversationId` with later messages to continue the same chat.

Short M4A recordings can be transcribed with `gpt-transcribe` through the audio
endpoint:

```bash
curl -X POST http://localhost:3000/api/transcribe \
  -H "Content-Type: audio/mp4" \
  --data-binary @recording.m4a
```

## Vercel

Create a Vercel project with this `backend` folder as its root directory, add
`OPENAI_API_KEY` and optionally `OPENAI_MODEL`, then deploy. Update both backend
URLs in the Android `ChatApi.kt` file to the resulting HTTPS endpoints.


### Confirmed device Clock tools

Clock tools pause using Agents SDK `interruptions` and serialized `RunState`.
The SSE response ends with `client.tools.requested` (call IDs, actions, arguments,
and an encrypted continuation). Android executes each action, then POSTs the
continuation and `toolResults` keyed by call ID to `/api/chat`. The resumed tool
returns the device result to the model before any final answer. This also works
across Vercel instances without an in-memory callback registry.

Continuation tokens expire after five minutes and use AES-GCM authenticated
encryption. Set `TOOL_CONTINUATION_SECRET` consistently across instances, or the
server derives the encryption key from `OPENAI_API_KEY`. Tokens contain private
run state and should not be logged. The client does not automatically replay
requests after network errors, avoiding duplicate Clock actions.

Clock changes require the app to be selected as Android's default digital
assistant and a Clock app that handles voice interaction. `EXTRA_SKIP_UI=true`
requests silent handling; third-party Clock apps can still display UI.
`CompleteVoiceRequest` confirms success; abort/launch errors report failure;
ambiguity reports `requires_user_action`; session loss or a 25-second callback
timeout reports `unknown`, never success. Only explicit `show_alarms` and
`show_timers` requests use ordinary activity launches (success means the page
launch was accepted). No fallback silently executes an unconfirmed change.

Validation: `npm test` exercises SDK pause/serialize/resume with each device
outcome, mismatched call IDs, token tampering and expiration. `npm run typecheck`
checks the backend. Device acceptance checks: set an alarm and timer, dismiss
and snooze an alarm, ambiguous label, unsupported Clock app, and no callback;
verify both chat and overlay, with the app selected and deselected as assistant.
