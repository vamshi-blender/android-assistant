# AI Assistant

An Android voice assistant that replaces the system assistant. It opens as an
overlay over whatever is on screen, streams answers from an OpenAI-backed agent,
and can act on the device — setting alarms and timers through the installed
Clock app.

Three ways to summon it:

| Trigger | Requires |
| --- | --- |
| Say **"Make it so"**, **"System go"** or **"Hey system"** | Microphone + the listener running |
| **Long-press the power button** | App set as the default assistant |
| **"Show Overlay"** button in the app | — |

Everything about the wake word runs **on-device** — no audio ever leaves the
phone. Only the chat message itself goes to the backend.

---

## How it fits together

```
                 ┌──────────────────────── Android app ────────────────────────┐
  "System go" ──►│ WakeWordService ─┐                                          │
  power button ──►  (sherpa-onnx)   ├─► AssistantTrigger ─┬─ unlocked → OverlayService
  in-app button ─►                  │                     └─ locked   → AssistantOverlayActivity
                 │                  │                                          │
                 │            ChatScreen ──► ChatApi ──(SSE)──┐                │
                 │                 ▲                          │                │
                 │                 └── DeviceClockToolExecutor │                │
                 └──────────────────────────────────────────── │ ──────────────┘
                                                               ▼
                                      ┌──────── backend (Node + Vercel) ────────┐
                                      │  /api/chat → @openai/agents             │
                                      │  tools: get_device_time,                │
                                      │         manage_device_clock             │
                                      └─────────────────────────────────────────┘
```

The backend streams Server-Sent Events. Two kinds of tool call come back:
server-side tools resolve on the backend, while `manage_device_clock` is
returned to the app as a **client tool** and executed locally by
[`DeviceClockToolExecutor`](app/src/main/java/com/vamshi/aiassistant/DeviceClockToolExecutor.kt),
which fires `AlarmClock` intents (`set_alarm`, `start_timer`, `snooze_alarm`,
`dismiss_alarm`, `show_alarms`, `show_timers`, `dismiss_expired_timers`).

---

## Setup

### 1. Backend

```bash
cd backend
cp .env.example .env        # then set OPENAI_API_KEY
npm install
npm run dev                 # listens on :3000
```

Full details, including Vercel deployment, in
[`backend/README.md`](backend/README.md).

### 2. Wake-word binaries

The engine and its model are ~63MB and are **not committed**. Fetch them before
the first build:

```bash
./scripts/fetch-wakeword-assets.sh
```

| What | Where |
| --- | --- |
| `sherpa-onnx-1.13.6.aar` (native libs + Kotlin API) | `app/libs/` |
| GigaSpeech English KWS model | `app/src/main/assets/sherpa-onnx-kws-zipformer-.../` |

> On Windows, run this from Git Bash. The repo has `core.autocrlf=true` and no
> `.gitattributes`, so a fresh checkout may give the script CRLF endings and
> `bash` will fail with `'\r': command not found`. Fix with
> `sed -i 's/\r$//' scripts/fetch-wakeword-assets.sh`, or add `*.sh text eol=lf`
> to a `.gitattributes`.

### 3. Build and connect

```bash
./gradlew assembleDebug
adb reverse tcp:3000 tcp:3000    # so the app reaches the local backend
```

`ChatApi.CHAT_URL` points at `http://localhost:3000/api/chat`; swap it for the
Vercel HTTPS URL when deploying.

### 4. Permissions, in the app

| Button | Grants |
| --- | --- |
| **Show Overlay** | "Display over other apps" |
| **Start Listening** | Microphone + notifications |
| **Set as Default Assistant** | Opens the system assistant picker — needed for the power-button trigger |

---

## Wake word

Detection uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) keyword
spotting with the streaming zipformer GigaSpeech model, entirely offline.

The active phrases live in:

```
app/src/main/assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/keywords.txt
```

```
▁MAKE ▁IT ▁SO :2.0 #0.15 @MAKE_IT_SO
▁SYSTEM ▁GO :2.0 #0.15 @SYSTEM_GO
▁HE Y ▁SYSTEM :2.0 #0.15 @HEY_SYSTEM
```

Any one of them fires. Add or remove lines freely — **no rebuild required**.
The notification reads this file at runtime, so it always lists what is actually
active, and `adb logcat -s NovaWakeWord` names whichever phrase matched.

That leading character is **U+2581**, SentencePiece's word-boundary marker — not
an underscore. `:2.0` is the boosting score, `#0.15` the trigger threshold, and
`@NAME` the label reported on a match.

### Tuning sensitivity

Tune in `keywords.txt`, not in Kotlin — the per-phrase suffixes override the
global config, so no recompile is needed.

```
▁SYSTEM ▁GO :2.0 #0.15
             │     └─ #threshold — trigger probability, 0..1
             └─────── :score     — boosting score
```

**No space between `:` or `#` and the number**, or the line is misparsed.

| Symptom | Change |
| --- | --- |
| Rarely fires when you say it | **lower** `#` (0.15 → 0.10), **raise** `:` (2.0 → 3.0) |
| Fires on unrelated speech | **raise** `#` (0.15 → 0.30), **lower** `:` |

Stock defaults are `:1.0` / `#0.25`; this project ships more sensitive values
because the stock ones under-triggered. Change one at a time — the threshold has
by far the larger effect.

The global fallbacks (`keywordsScore` / `keywordsThreshold`) in
[`SherpaWakeWordDetector.kt`](app/src/main/java/com/vamshi/aiassistant/wakeword/SherpaWakeWordDetector.kt)
apply only to lines with no suffix.

---

## Changing the wake word

> **The model matches BPE token sequences, not plain text.** Typing
> `HEY JARVIS` into `keywords.txt` means it will *never fire* — silently. No
> error, no log line. Always regenerate the tokens.

### Step 1 — generate the tokens

You need `bpe.model`, which ships in the model tarball but is not copied into
assets (it is only needed at authoring time). Extract it, then:

```bash
python -m venv venv
./venv/Scripts/python -m pip install sentencepiece   # Linux/macOS: ./venv/bin/python

python - <<'PY'
import sentencepiece as spm
D = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
sp = spm.SentencePieceProcessor(); sp.load(f"{D}/bpe.model")

# Sanity-check the method: re-tokenise the phrases bundled with the model and
# diff against its own keywords.txt. If this assert fails, do not trust output.
raw = [l.strip() for l in open(f"{D}/keywords_raw.txt") if l.strip()]
exp = [l.strip() for l in open(f"{D}/keywords.txt", encoding="utf-8") if l.strip()]
assert all(" ".join(sp.encode_as_pieces(r)) == e for r, e in zip(raw, exp))

vocab = {l.split()[0] for l in open(f"{D}/tokens.txt", encoding="utf-8") if l.split()}
pieces = sp.encode_as_pieces("MAKE IT SO")           # <-- your phrase, UPPERCASE
print(" ".join(pieces))
print("missing from vocab:", [p for p in pieces if p not in vocab] or "none")
print("lone chars:", sum(1 for p in pieces if len(p.lstrip("▁")) == 1))

# Whole-word tokens - build your phrase from these for the strongest match.
print("\nwhole words:", ", ".join(sorted(
    w.lstrip("▁") for w in vocab
    if w.startswith("▁") and w.lstrip("▁").isalpha() and len(w) > 4)))
PY
```

All three checks matter: the assert must pass, `missing from vocab` must be
`none`, and `lone chars` should be **0**.

The equivalent official tool, if you prefer it:

```bash
pip install sherpa-onnx
echo "MAKE IT SO" > raw.txt
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type bpe \
    --bpe-model bpe.model raw.txt keywords.txt
```

### Step 2 — update both places

| File | What to change |
| --- | --- |
| `app/src/main/assets/.../keywords.txt` | the token line — **the only thing that affects detection** |
| [`scripts/fetch-wakeword-assets.sh`](scripts/fetch-wakeword-assets.sh) | the matching `printf`, or a re-fetch reverts your change |

Nothing in Kotlin needs touching: the notification reads the phrases from the
asset, and the button just says "Start Listening".

Write `keywords.txt` as UTF-8 with LF endings. Verify with `od -c keywords.txt`
— the marker must be the three bytes `342 226 201`.

### Picking a phrase that actually works

This matters far more than it sounds. Several obvious-looking phrases failed
on-device before the current three stuck.

**1. Token fragmentation.** A phrase that shreds into single characters is a
weak match target:

| Phrase | Tokens | Result |
| --- | --- | --- |
| `HEY BUDDY` | `▁HE Y ▁BU D D Y` | 4 lone chars — barely ever fired |
| `HEY NOVA` | `▁HE Y ▁NO V A` | 3 lone chars — weak |
| `OK GOOGLE` | `▁O K ▁GO O G LE` | 4 lone chars — hopeless |
| `HEY SYSTEM` | `▁HE Y ▁SYSTEM` | 1 lone char — reliable |
| `MAKE IT SO` | `▁MAKE ▁IT ▁SO` | **0 — every word whole** |
| `SYSTEM GO` | `▁SYSTEM ▁GO` | **0 — every word whole** |

Build phrases from words the vocabulary holds as **whole tokens** (~170 of
them; dump the list with the script above). Note `HEY` itself costs two tokens
(`▁HE Y`), so two whole words beats `HEY <word>`.

**2. Length.** Clean tokenisation is necessary but *not sufficient* — the phrase
also has to be long enough. Single-word names were tested and **none of them
fired at all**, even ones that tokenise perfectly:

| | Syllables | Result |
| --- | --- | --- |
| `MAKE IT SO`, `SYSTEM GO`, `HEY SYSTEM` | 3 | reliable |
| `HIRO` (`▁HI RO`), `MILO` (`▁MI LO`), `BAYMAX` | 2 | never fired |

A two-syllable word is only ~400ms of audio — too little for the streaming
model to commit. **Aim for 3–4 syllables.** If you want a character name, pad it
into a longer phrase (`HEY BAYMAX`), and expect to fight the fragmentation that
reintroduces.

**3. Accent.** GigaSpeech is mostly American English. If you speak Indian or
other non-American English, avoid:

- **Intervocalic `T`/`D`** — flapped in American English ("buddy" → *bu-ree*),
  a clear retroflex stop in Indian English; acoustically very different. This is
  why `HEY BUDDY` failed. Also rules out *water, better, matter, city*.
- **`W` vs `V`** — merged in many Indian accents, but only where `W` is a real
  consonant (word-initial or after a consonant). In `OW`/`AW`/`EW` it is part of
  the vowel, so `POWER` and `HOUSE` are safe.
- **`TH`** — often realised as `T`/`D`. Rules out *together, everything*.

**4. Don't pick something you would say anyway.** Matching is on the whole
sequence, so `HEY JUST` would fire on *"hey, just a second"*. Most whole-word
tokens in this vocabulary are common filler words.

Multiple phrases are allowed, one per line — useful for A/B testing which one
your voice hits, since logcat names the match.

---

## Project layout

| Path | Role |
| --- | --- |
| [`MainActivity.kt`](app/src/main/java/com/vamshi/aiassistant/MainActivity.kt) | Home screen: chat, overlay, assistant picker, listener toggle |
| [`ChatScreen.kt`](app/src/main/java/com/vamshi/aiassistant/ChatScreen.kt) | Chat UI — streams commentary and the final answer separately |
| [`ChatApi.kt`](app/src/main/java/com/vamshi/aiassistant/ChatApi.kt) | SSE client; emits typed `ChatStreamEvent`s |
| [`DeviceClockToolExecutor.kt`](app/src/main/java/com/vamshi/aiassistant/DeviceClockToolExecutor.kt) | Runs client-side clock tools via `AlarmClock` intents |
| [`assist/`](app/src/main/java/com/vamshi/aiassistant/assist/) | `VoiceInteractionService` trio that makes the app the default assistant |
| [`overlay/AssistantTrigger.kt`](app/src/main/java/com/vamshi/aiassistant/overlay/AssistantTrigger.kt) | Single entry point; routes on lock state |
| [`overlay/OverlayService.kt`](app/src/main/java/com/vamshi/aiassistant/overlay/OverlayService.kt) | Overlay window when unlocked |
| [`overlay/AssistantOverlayActivity.kt`](app/src/main/java/com/vamshi/aiassistant/overlay/AssistantOverlayActivity.kt) | Lock-screen host (`showWhenLocked`) |
| [`overlay/OverlayContent.kt`](app/src/main/java/com/vamshi/aiassistant/overlay/OverlayContent.kt) | The panel UI, shared by both |
| [`wakeword/WakeWordService.kt`](app/src/main/java/com/vamshi/aiassistant/wakeword/WakeWordService.kt) | Foreground service (`microphone`) owning the mic |
| [`wakeword/WakeWordDetector.kt`](app/src/main/java/com/vamshi/aiassistant/wakeword/WakeWordDetector.kt) | Engine interface — swap engines without touching capture or launch |
| [`wakeword/SherpaWakeWordDetector.kt`](app/src/main/java/com/vamshi/aiassistant/wakeword/SherpaWakeWordDetector.kt) | sherpa-onnx implementation |
| [`wakeword/StubWakeWordDetector.kt`](app/src/main/java/com/vamshi/aiassistant/wakeword/StubWakeWordDetector.kt) | No-model fallback; logs mic level to prove capture works |
| [`backend/`](backend/) | Node SSE server on `@openai/agents` |

### Design notes

- **All triggers funnel through `AssistantTrigger`**, which checks
  `isKeyguardLocked`. The split exists because `TYPE_APPLICATION_OVERLAY`
  windows **cannot draw above the keyguard**; the activity declares
  `showWhenLocked`/`turnScreenOn` instead. It does *not* dismiss the keyguard —
  anything sensitive should first call `KeyguardManager.requestDismissKeyguard()`.
- **The listener never requests audio focus**, so music keeps playing while it
  listens — necessary for media-control commands.
- **The overlay window is full-screen** even though the panel occupies the
  bottom 30%, so it owns all touch input. A tap outside the panel dismisses it
  and is swallowed, rather than passing through to the app underneath.

---

## Testing

```bash
# Detections and mic level
adb logcat -s NovaWakeWord

# Fire the overlay without speaking (debug builds only).
# The only way to test the lock-screen path, where no in-app button is reachable.
adb shell am broadcast -a com.vamshi.aiassistant.action.SIMULATE_WAKE
```

### Diagnosing "it doesn't trigger"

The log prints a peak input level once a second, which separates the failure
modes:

| Log shows | Meaning |
| --- | --- |
| `listening, peak level = 0.000` while you speak | Mic is not reaching the engine — permission, or another app holding it |
| No `listening` lines at all | Capture died, or the service is not running |
| Healthy peaks (>0.05) but no `matched wake phrase` | Audio is fine — it is a tuning or phrase problem |
| Peak pinned near `1.000` | Clipping — too loud or too close, which also hurts recognition |

---

## Known platform limits

- **No boot autostart.** A `microphone`-type foreground service cannot be
  started from the background on Android 12+, so the app must be opened once per
  reboot. `START_STICKY` restarts are refused for the same reason.
- **Persistent notification** is mandatory for a mic foreground service, as is
  the privacy indicator dot.
- **Mic contention.** A phone call or another app capturing audio preempts the
  listener. The read loop tolerates transient failures and reopens a dead audio
  device, but a sustained loss stops the service rather than leaving the
  notification claiming to listen.
- **Loud playback** hurts detection — there is no acoustic echo cancellation on
  this path. Consider ducking media on detect.
- **Power-button mapping is OEM-controlled.** Being the default assistant is
  what Pixel/AOSP maps to long-press; some skinned ROMs hardwire it to their own
  assistant with no override.

### APK size

~110MB, because it carries both `arm64-v8a` and `x86_64` native libs
(onnxruntime alone is ~21MB per ABI). Dropping `x86_64` from `abiFilters` in
[`app/build.gradle.kts`](app/build.gradle.kts) roughly halves it, at the cost of
emulator support. The model also has `int8` variants (~9MB smaller, cheaper on
battery, slightly less accurate) — swap them in `fetch-wakeword-assets.sh` and
`SherpaWakeWordDetector`.

---

## Why sherpa-onnx and not Picovoice

Picovoice **discontinued its free tier on 30 June 2026 and disabled existing
free AccessKeys**, with no non-commercial tier planned. Its SDK refuses to run
without a valid key — a remote kill switch on locally-running code.

sherpa-onnx is Apache-2.0, needs no key or account, and cannot be switched off
remotely. The tradeoff is the manual BPE tokenisation and threshold tuning
described above.
