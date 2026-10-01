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

Everything about the wake word runs **on-device**. Audio recorded with the chat
microphone button is sent to the backend for transcription; the always-on wake
word audio never leaves the phone. Chat recording waits up to 5 seconds for
speech, ends after 3 continuous seconds of silence, and never exceeds 30 seconds
or 5 MB. Audio is discarded when no speech is detected.

Voice activity uses normalized peak amplitude. Tune
`VoiceActivityConfig.DEFAULT_AUDIO_THRESHOLD` in `VoiceActivityDetector.kt`
(default `0.04`, valid range `0.0`–`1.0`) for the target device and environment.
While speech is above that threshold, the red microphone button scales
with the smoothed audio level without changing its layout or touch target.

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
                                      │         manage_device_clock,            │
                                      │         get_device_status,              │
                                      │         set_device_setting,             │
                                      │         switch_wifi_network             │
                                      └─────────────────────────────────────────┘
```

The backend streams Server-Sent Events. Two kinds of tool call come back:
server-side tools resolve on the backend, while `manage_device_clock` is
returned to the app as a **client tool** and executed locally by
[`DeviceClockToolExecutor`](app/src/main/java/com/vamshi/aiassistant/DeviceClockToolExecutor.kt),
which fires `AlarmClock` intents (`set_alarm`, `start_timer`, `snooze_alarm`,
`dismiss_alarm`, `show_alarms`, `show_timers`, `dismiss_expired_timers`).
`get_device_status`, `set_device_setting` and `switch_wifi_network` are client
tools too, run by
[`DeviceSettingsToolExecutor`](app/src/main/java/com/vamshi/aiassistant/DeviceSettingsToolExecutor.kt)
to read or change Wi-Fi, mobile data, Bluetooth, airplane mode and brightness,
and to switch between saved Wi-Fi networks.

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

`adb reverse` is required when testing on an emulator or a USB-connected phone
because Android's `localhost` refers to the Android device, not the development
computer. Run it again whenever the device reconnects or reboots:

```powershell
adb reverse tcp:3000 tcp:3000
```

The URLs in `ChatApi` point at `http://localhost:3000`; swap both for the Vercel
HTTPS deployment URLs when deploying.

### 4. Permissions, in the app

| Button | Grants |
| --- | --- |
| **Show Overlay** | "Display over other apps" |
| **Start Listening** | Microphone + notifications |
| **Set as Default Assistant** | Opens the system assistant picker — needed for the power-button trigger |
| **More → Grant permission** | "Modify system settings" — needed for the brightness slider |
| **More → any Wi-Fi / mobile data / airplane switch** | The Shizuku access prompt — see [Phone controls and Shizuku](#phone-controls-and-shizuku) |

---

## Phone controls and Shizuku

The **More** button on the home screen opens a page that shows and changes the
phone's connectivity and brightness. The same controls are available to the AI
as tools (`get_device_status`, `set_device_setting`, `switch_wifi_network` — see
[`backend/README.md`](backend/README.md#phone-settings-tools)).

| Control | How it works | Needs Shizuku |
| --- | --- | --- |
| Wi-Fi on/off | `svc wifi enable\|disable` | Yes |
| Mobile data on/off | `svc data enable\|disable` | Yes |
| Airplane mode on/off | `cmd connectivity airplane-mode enable\|disable` | Yes |
| Wi-Fi network list and switching | `cmd wifi` scan, then a helper that asks the Wi-Fi service to join a *saved* network by id (no password needed) | Yes |
| Bluetooth on/off | The app switches it itself with `BLUETOOTH_ADMIN` on Android 11 and older | No (Android 11 or older) |
| Brightness slider | Writes the system brightness; needs the "Modify system settings" permission | No |
| Reading the current state | Public settings and `WifiManager` | No |

### Why Shizuku

Android does not let ordinary apps flip these radios: `WifiManager.setWifiEnabled`
does nothing for apps targeting Android 10+, apps cannot change mobile data or
airplane mode at all, and Android 13+ blocks Bluetooth toggling. The `adb shell`
user *can* do all of it. [Shizuku](https://shizuku.rikka.app/) is a free,
open-source app that starts a small service with that shell-level privilege and
lets apps you approve send it commands — no root needed.

Shizuku is optional. Without it the app still runs; the Wi-Fi, mobile data and
airplane switches fall back to opening the matching Android settings screen, and
the AI tools return `requires_user_action` asking you to start Shizuku.

### Set up Shizuku

1. **Install Shizuku** on the phone from the
   [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
   or the [GitHub releases](https://github.com/RikkaApps/Shizuku/releases)
   (the app was tested with 13.5).
2. **Enable Developer options and USB debugging** (Settings → About phone → tap
   *Build number* seven times, then Settings → Developer options → *USB debugging*).
3. **Start the Shizuku service.** Pick one:
   - **From a computer (USB):** open the Shizuku app once, then run
     ```bash
     P=$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed 's/package://; s#/base.apk##')
     adb shell "$P/lib/arm64/libshizuku.so"      # use lib/arm/ on a 32-bit phone
     ```
     (In Git Bash on Windows, `export MSYS_NO_PATHCONV=1` first so paths are not
     rewritten.) Shizuku's own *Start via connected computer* screen shows an
     equivalent `start.sh` command.
   - **On the phone only (Android 11+):** in the Shizuku app choose *Start via
     Wireless debugging*, turn on Wireless debugging in Developer options, pair
     when prompted, then tap *Start*.
4. **Check it is running.** The Shizuku app says *Shizuku is running*; from a
   computer, `adb shell ps -A | grep shizuku_server` shows the process.
5. **Approve this app.** Open **More** and tap any Wi-Fi, mobile data or
   airplane switch. Shizuku asks *Allow AI Assistant to access Shizuku?* — choose
   **Allow all the time**. After that the switches change the setting directly.

**Shizuku stops whenever the phone reboots.** Start it again (step 3) after each
restart. Approval of the app is remembered.

#### Realme / Oppo (ColorOS) and other restricted ROMs

Some manufacturers strip permissions from the `adb` user. The symptom is a
*"The permission of adb is limited"* dialog when you tap a switch, and a red
*"You need to take an extra step"* card in the Shizuku app. On Realme/Oppo
(ColorOS) turn on **Settings → Developer options → Disable permission
monitoring**, then restart Shizuku (step 3). Other makers have similar switches
(for example Xiaomi's *USB debugging (Security settings)*); the red card's
*Read help* button opens Shizuku's guide for your phone.

> **Security note.** An app you approve in Shizuku can run shell-level commands,
> and *Disable permission monitoring* turns off a manufacturer safeguard. Only
> approve apps you trust. You can revoke access in the Shizuku app under
> *Authorized applications*, and turn the developer option off again when you are
> done.

### Bluetooth

Bluetooth is deliberately **not** done through Shizuku: ColorOS denies the shell
user `BLUETOOTH_ADMIN`, so the shell commands fail. On Android 11 and older the
app calls `BluetoothAdapter` directly. On Android 12+ it falls back to opening
the Bluetooth settings screen, and Android 13+ does not allow apps to toggle
Bluetooth at all.

### Wi-Fi switching

The list shows every network in range, marked *Connected*, *Saved*, *Secured* or
*Open*. Tapping a **saved** network switches to it. Tapping any other network
opens Android's Wi-Fi panel, where Android shows its own password popup — a
specific network cannot be deep-linked on Android 11. Under the hood, switching
runs [`WifiSwitchMain`](app/src/main/java/com/vamshi/aiassistant/WifiSwitchMain.kt)
as the shell user through `app_process`; it asks the Wi-Fi service to enable the
saved network by id. WEP and enterprise (EAP) networks are not listed as
joinable.

### Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Switches open a settings screen instead of toggling | Shizuku is not running or the app is not approved. Start Shizuku (step 3) and approve the prompt. |
| *"The permission of adb is limited"* | ColorOS/OEM restriction — see the Realme/Oppo section above, then restart Shizuku. |
| Wi-Fi list shows *"Allow Shizuku access…"* | Same as above; tap the Wi-Fi switch to trigger the approval prompt. |
| Bluetooth switch opens settings | Android 12 or newer, or the ROM blocks it. Use the settings screen. |
| Brightness shows *Grant permission* | Grant "Modify system settings" for the app, then return to the page. |
| Wi-Fi switch fails with *Could not connect* | The saved password is out of date or the signal is weak — reconnect once from Android's Wi-Fi settings. |
| `adb` says *more than one device* | The phone appears over USB and wireless debugging. Use `adb -s <serial>` or set `ANDROID_SERIAL`. |
| Everything stopped working after a reboot | Shizuku does not survive a reboot — start it again. |

### Release builds

Debug builds are not minified. If you turn on R8/ProGuard, keep
`com.vamshi.aiassistant.WifiSwitchMain` (it is started by class name through
`app_process`) and the Shizuku API classes
(`rikka.shizuku.**`) — `ShizukuRadios` reaches `Shizuku.newProcess` by
reflection because the API made it private in version 13.

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
| [`ChatApi.kt`](app/src/main/java/com/vamshi/aiassistant/ChatApi.kt) | Chat SSE and audio-transcription client |
| [`DeviceClockToolExecutor.kt`](app/src/main/java/com/vamshi/aiassistant/DeviceClockToolExecutor.kt) | Runs client-side clock tools via `AlarmClock` intents |
| [`DeviceSettingsToolExecutor.kt`](app/src/main/java/com/vamshi/aiassistant/DeviceSettingsToolExecutor.kt) | Runs the phone-settings tools (status, toggles, brightness, Wi-Fi switching) |
| [`DeviceTools.kt`](app/src/main/java/com/vamshi/aiassistant/DeviceTools.kt) | Routes a backend tool request to the clock or settings executor |
| [`MoreScreen.kt`](app/src/main/java/com/vamshi/aiassistant/MoreScreen.kt) | The **More** page: connectivity section plus the brightness slider |
| [`ConnectivityToggles.kt`](app/src/main/java/com/vamshi/aiassistant/ConnectivityToggles.kt) | Wi-Fi, mobile data, Bluetooth and airplane mode switches |
| [`ShizukuRadios.kt`](app/src/main/java/com/vamshi/aiassistant/ShizukuRadios.kt) | Runs shell commands through Shizuku (radio toggles, command output) |
| [`WifiNetworks.kt`](app/src/main/java/com/vamshi/aiassistant/WifiNetworks.kt) / [`WifiNetworksSection.kt`](app/src/main/java/com/vamshi/aiassistant/WifiNetworksSection.kt) | Scans networks and switches to saved ones; the list UI |
| [`WifiSwitchMain.kt`](app/src/main/java/com/vamshi/aiassistant/WifiSwitchMain.kt) | Shell-user helper (run via `app_process`) that joins a saved network by id |
| [`backend/src/tool-registry.ts`](backend/src/tool-registry.ts) | Shared tool definitions and the tool sets exposed to chat and live agents |
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
- **Shizuku does not survive a reboot.** Wi-Fi, mobile data and airplane mode
  control stops until Shizuku is started again (see
  [Phone controls and Shizuku](#phone-controls-and-shizuku)). Some ROMs, such as
  Realme's ColorOS, also need *Disable permission monitoring* turned on.
- **Bluetooth toggling is limited to Android 11 and older**, and Wi-Fi
  switching only works for networks already saved on the phone.
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
