# AI Assistant backend

## Local setup

1. Copy `.env.example` to `.env` and set `OPENAI_API_KEY` and `GROQ_API_KEY`.
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

Audio uploads are limited to 4 MB so they remain below Vercel Functions' 4.5 MB
request-body limit. The Android client checks this before starting an upload,
and the backend independently enforces the same limit.

## Vercel

Create a Vercel project with this `backend` folder as its root directory, add
`OPENAI_API_KEY`, `GROQ_API_KEY`, `TOOL_CONTINUATION_SECRET`, `APP_API_KEY`, and optionally
`OPENAI_MODEL`, then deploy. The Android backend choices are defined in
`BackendSettings.kt`.

The home screen's Agent model setting applies to both text chat and Live
delegation. OpenAI uses the existing hosted Responses paths. Groq uses
`openai/gpt-oss-20b` through the Agents SDK's OpenAI-compatible Chat Completions
provider. Live
voice still uses OpenAI `gpt-live-1`; only its delegated backend work uses Groq.
Both text chat and Live delegation can search the web for current information.

All endpoints require `X-API-Key`. For Android builds, provide the matching
`APP_API_KEY` through the environment or the ignored root `local.properties`:

```properties
APP_API_KEY=your-random-shared-key
```

This is intentionally lightweight protection for a hobby app. Since the key is
included in the APK, it should not be treated as strong user authentication.

### Live voice

The overlay's Live button starts a fresh conversational `gpt-live-1` session.
`POST /api/live` accepts `{ "sdp": "<WebRTC offer>" }` and returns the Live
session ID and SDP answer. It uses the same `APP_API_KEY` authentication and
requires an `OPENAI_API_KEY` with GPT-Live access. This route runs locally and
on Vercel; microphone and speaker audio travel directly between Android and
OpenAI over WebRTC after setup. Delegated backend work provides web search, the
supported Android Clock actions, and the [phone-settings tools](#phone-settings-tools).

The app displays both speakers' live captions, supports microphone mute, and
ends the session when you tap end or dismiss the overlay. Each Live call has
its own conversation context, separate from regular text chat. Test on a device
with microphone permission: start Live, speak, interrupt a reply, toggle mute,
end, and start again. Confirm the microphone is released after dismissal and
the wake-word listener resumes if it was running before the call.

Protocol reference: [GPT-Live WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc?api=live).


### Confirmed device Clock tools

Tool schemas and agent-specific availability are centralized in
`src/tool-registry.ts`. Chat and live voice both see one
`manage_device_clock` tool containing the supported Clock actions. Live voice
additionally receives `get_device_time` and `end_session`; those tools are not
exposed to text chat. The phone-settings tools described
[below](#phone-settings-tools) are available to every agent.

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

### Phone-settings tools

Three more device tools use the same pause/resume flow as the Clock tool. They
are offered to every agent (OpenAI and Groq chat, OpenAI and Groq live voice):

| Tool | Arguments | Effect |
| --- | --- | --- |
| `get_device_status` | `includeWifiNetworks?` | Wi-Fi (and connected network), mobile data, Bluetooth, airplane mode, brightness; optionally the Wi-Fi networks in range |
| `set_device_setting` | `setting` (`wifi`, `mobile_data`, `bluetooth`, `airplane_mode`, `brightness`), `enabled?`, `level?` | Sets one setting to an explicit state; `level` is 0-100 for brightness |
| `switch_wifi_network` | `ssid` | Switches to a saved network that is in range |

Unlike `manage_device_clock`, they are sent to the phone under their own names
(see `toDeviceRequest` in `src/tool-registry.ts`). Android runs them in
`DeviceSettingsToolExecutor`. Turning off the connection the conversation is
using (Wi-Fi with no mobile data, mobile data while on cellular, or turning on
airplane mode) is applied about three seconds after the result is returned, and
the result says so. Wi-Fi, mobile data and airplane mode need Shizuku running
and approved for the app; without it the tools return `requires_user_action`.
Setup steps are in the root README's
[Phone controls and Shizuku](../README.md#phone-controls-and-shizuku) section.

Behavior the prompts and tool descriptions rely on:

- `set_device_setting` always takes an explicit target (`enabled: true|false`,
  or `level` for brightness), never a toggle, and normally reports success only
  after reading the state back. A request that was sent but has not taken effect
  returns `unknown`, which the model must not retry automatically. The
  connection-cutting changes above are the exception: they are scheduled, so the
  result says they are not verified yet.
- Brightness needs the "Modify system settings" permission and turns
  auto-brightness off. Bluetooth is only switchable on Android 11 and older.
- `switch_wifi_network` only joins saved networks that are in range; an unsaved
  one returns `requires_user_action` because it needs a password.
- Tool results are capped at 2000 characters by the `toolResults` validation in
  `http.ts` and `live-delegate.ts`, so `get_device_status` drops the weakest
  Wi-Fi networks to fit.
- The tools are device-executed, so they use `needsApproval: true` purely as the
  pause mechanism (it is not a user confirmation prompt). Their schemas set
  `additionalProperties: true` because the Agents SDK's non-strict tool type
  requires it.

**Groq schema rule.** Groq rejects a function sent with `strict: true` and an
empty `required` list (`400 ... 'required' present but 'properties' is
missing`), which made every Groq Live delegation fail while `end_session` was
defined that way. The Groq-only `end_session` tool in `src/tool-registry.ts` is
therefore non-strict, and a test fails if a Groq tool is strict with an empty
`required` list. The OpenAI Live definition of `end_session` and
`get_device_time` stays strict.

Deploy the backend and the app together: a backend that offers these tools needs
an app that can run them, and an older backend does not offer them at all.

Validation: `npm test` exercises SDK pause/serialize/resume with each device
outcome (including each phone-settings tool), mismatched call IDs, token
tampering and expiration, that every agent tool list exposes the phone-settings
tools, and the Groq schema rule. `npm run typecheck` checks the backend. Device
acceptance checks: set an alarm and timer, dismiss and snooze an alarm,
ambiguous label, unsupported Clock app, and no callback; verify both chat and
overlay, with the app selected and deselected as assistant. For the
phone-settings tools, with Shizuku running: read the status, toggle each radio
off and on, set brightness, and switch between two saved Wi-Fi networks, in
chat, overlay and Live voice with both OpenAI and Groq. Then stop Shizuku and
confirm the radio and Wi-Fi tools report `requires_user_action`.
