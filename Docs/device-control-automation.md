# Device Control & Automation — Design Notes

**Status:** design document, nothing here is implemented yet.
**Written:** 2026-08-29
**Audience:** whoever (human or AI) picks up this feature next.

---

## 0. How to use this document

The goal is that an implementer can read this and start building **without
re-deriving anything and without guessing**. So:

- Claims are marked **[VERIFIED]** when they were tested on real hardware during
  the session that produced this document, and **[UNVERIFIED]** when they come
  from documentation or reasoning but were not run.
- Where a competing suggestion exists, it is quoted and assessed rather than
  silently dropped.
- Section 10 (Google Play policy) contains a finding that constrains the whole
  design. **Read it before writing code**, not after.

Test device used throughout: **Realme RMX1901, Android 11 (SDK 30)**, ColorOS,
Oppo launcher. Project is `minSdk 30`, `targetSdk 36`.

---

## 1. The goal

Let the assistant *do things on the phone* — open apps, tap, type, navigate,
read the screen — **without a PC attached**, driven by the AI rather than by a
hardcoded script.

The app already does a narrow version of this: `manage_device_clock` sets alarms
and timers through `AlarmClock` intents. This document is about generalising
that from "a few known intents" to "drive arbitrary UI".

---

## 2. What was already proven with ADB (and why it matters)

Before designing anything, the whole interaction loop was validated end-to-end
against the real device over USB. **Every capability below was actually
executed** — this is not a list of things that ought to work.

### 2.1 The loop that worked

```
observe (read UI state) → decide (pick a target) → act (tap/type) → verify (re-read)
```

The critical insight: **never guess coordinates.** Dump the view hierarchy,
find the element by id, compute its centre from `bounds`, tap that. When
coordinates were hardcoded instead, taps missed — the "Show Overlay" button moved
from y=1194 to y=1128 between runs and every blind tap failed.

### 2.2 Commands used, and what each proved

| Command | Purpose | Result |
| --- | --- | --- |
| `adb devices` | Confirm authorised device | **[VERIFIED]** `24f0765a device` |
| `adb install -r app-debug.apk` | Deploy build | **[VERIFIED]** |
| `adb shell pm list packages \| grep -i calc` | Find a package | **[VERIFIED]** → `com.coloros.calculator` |
| `adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>` | Find launch activity | **[VERIFIED]** → `com.android.calculator2.Calculator` |
| `adb shell am start -n <pkg>/<activity>` | Launch app | **[VERIFIED]** |
| `adb shell am force-stop <pkg>` | Kill app | **[VERIFIED]** |
| `adb shell uiautomator dump /sdcard/c.xml` | Snapshot the view tree | **[VERIFIED]** — gave `resource-id`, `text`, `content-desc`, `bounds`, `clickable` for every node |
| `adb shell input tap X Y` | Tap | **[VERIFIED]** |
| `adb shell input text "Hello%sthere"` | Type (`%s` = space) | **[VERIFIED]** |
| `adb shell input keyevent KEYCODE_ENTER\|BACK\|HOME` | Key press | **[VERIFIED]** |
| `adb exec-out screencap -p > shot.png` | Screenshot (binary-safe) | **[VERIFIED]** |
| `adb shell dumpsys window \| grep mCurrentFocus` | Which window has focus | **[VERIFIED]** — this is what proved the Home-button bug |
| `adb shell dumpsys activity services <pkg>` | Is a service alive | **[VERIFIED]** |
| `adb logcat -d -b crash` | Real stack traces | **[VERIFIED]** — found `ViewTreeLifecycleOwner not found` |

### 2.3 Worked example: the calculator run

1. `pm list packages | grep -i calc` → `com.coloros.calculator`
2. `cmd package resolve-activity --brief` → `com.android.calculator2.Calculator`
3. `am start -n …` → launched
4. `dumpsys window | grep mCurrentFocus` → confirmed foreground
5. `uiautomator dump` → read every button's `resource-id` + `bounds`
6. Computed centres: `digit_2` (396,1953), `op_add` (900,1953), `eq` (900,2171), `clr` (156,1301)
7. `input tap` ×3 for `2 + 2`, then `=`
8. Re-dumped, read `id/result` node's `text` attribute → **`4`**
9. Tapped `clr`, re-dumped → `text=""`, `content-desc="No result"`
10. `am force-stop`, verified focus returned to launcher

**The answer was read from the view tree's `text` attribute — no screenshot, no
OCR.** That is the pattern to carry into the app: structured UI data beats pixels
whenever it is available.

### 2.4 Environment gotchas found the hard way

- **Git Bash mangles device paths.** `/sdcard/x.xml` becomes
  `C:\Program Files\Git\sdcard\x.xml`. Fix: `export MSYS_NO_PATHCONV=1`.
- **`adb shell cat file.png > out.png` corrupts binaries** on Windows (CRLF
  translation). Use `adb exec-out screencap -p` instead.
- **uiautomator attribute order is `index, text, resource-id, class, package,
  content-desc, …`** — `text` comes *before* `resource-id`. A regex assuming the
  reverse silently matches nothing.

---

## 3. Why ADB cannot ship inside the app

This is the hard constraint that shapes everything else.

`adb shell` runs as the **`shell` user (uid 2000)** — a privilege level the
system grants to a *trusted USB/network host* after the user authorises the
host's RSA key. An installed app runs as its **own uid** inside the app sandbox.

Concretely, an app can never call:

| ADB capability | Blocked by |
| --- | --- |
| `input tap` / `input text` | `INJECT_EVENTS` — signature permission |
| `uiautomator dump` | shell-only binary, not exposed to apps |
| `am force-stop <other pkg>` | `FORCE_STOP_PACKAGES` — signature permission |
| `screencap` of the whole screen | `READ_FRAME_BUFFER` — signature permission |

There is no permission an ordinary app can request to get these. Shipping the
ADB approach would require the device to be rooted, or a PC permanently
tethered. **[VERIFIED by design of the Android permission model; not something
to re-test.]**

**But ADB stays extremely valuable as a prototyping tool** — see §11.

---

## 4. Assessment of the external proposal

The proposal in `android_ai_assistant_device_control.md` is **broadly correct and
a good starting point.** Assessment point by point:

### 4.1 What it gets right — adopt these

| Proposal | Verdict |
| --- | --- |
| AI returns a **structured action**, not shell commands | ✅ **Correct and important.** This is the single most important decision in the design. |
| Whitelisted tool set (`open_app`, `tap`, `type_text`, …) | ✅ Correct. |
| Accessibility Service as the main execution layer | ✅ Correct — this is the sanctioned API. |
| Prefer native APIs/Intents where Android exposes them | ✅ Correct, and this should be stated more strongly: intents are *first choice*, not a fallback. |
| Shizuku for things apps cannot do | ✅ Valid, with heavy caveats — see §8. |
| Prototype with ADB, then reimplement in-app | ✅ Correct and exactly what §11 describes. |
| "AI should not receive unrestricted shell access" | ✅ Correct. |

### 4.2 Where it is wrong or incomplete — do not follow as written

**a) MediaProjection is the wrong tool for screen vision here.**
The proposal recommends `MediaProjection` for AI vision. On this project's
`minSdk 30` there is a strictly better option:
`AccessibilityService.takeScreenshot()`, added in **API 30 / Android 11**.

| | MediaProjection | `AccessibilityService.takeScreenshot()` |
| --- | --- | --- |
| Consent dialog | Every session | None (service already authorised) |
| Persistent notification / cast icon | Yes | No |
| Extra permission | Yes | No — comes with the service |
| Min API | 21 | 30 (= our minSdk) |

Use `takeScreenshot()`. Keep MediaProjection in mind only if you ever need
continuous video rather than stills. **[VERIFIED: API level 30 confirmed from
Android reference docs. UNVERIFIED: not yet run on device.]**
Note there is a rate limit (~333 ms minimum between captures, roughly 3/sec);
exceeding it returns an interval-too-short error rather than a bitmap.

**b) It omits the screen-reading primitive that matters most.**
The doc frames vision as screenshots + AI understanding. But the accessibility
**node tree** (`rootInActiveWindow`) gives structured text, view ids, bounds and
available actions — exactly what `uiautomator dump` gave over ADB. That is far
cheaper, faster and more reliable than sending images to a vision model.

> **Design rule: read the node tree first. Use a screenshot only when the tree
> is unusable** (canvas-drawn UI, games, WebView without semantics, image-only
> content).

Screenshots also cost tokens and latency on every turn; the node tree can be
filtered to just interactive elements before it ever reaches the model.

**c) It does not mention the Google Play policy problem at all.**
This is the biggest omission. See §10 — it may determine whether this feature can
ever be distributed.

**d) `get_current_app()` in its tool list needs care.**
Reading the foreground app is *not* freely available to apps on modern Android.
`getRunningTasks` is restricted, and `UsageStatsManager` needs the special
`PACKAGE_USAGE_STATS` grant. From inside an AccessibilityService it *is*
available — `AccessibilityEvent.getPackageName()` / `rootInActiveWindow
.packageName` — so implement it there, not via ActivityManager. **[UNVERIFIED]**

---

## 5. Recommended architecture

Layered, cheapest and most reliable first. **Always resolve an action at the
lowest layer that can serve it.**

```
                       User request
                            │
                    AI (backend agent)
                            │
                 emits a structured tool call
                            │
              ┌─────────────▼─────────────┐
              │  Android Action Router     │   (validates, routes, audits)
              └─────────────┬─────────────┘
        ┌────────────┬──────┴──────┬───────────────┐
        ▼            ▼             ▼               ▼
   Layer 0      Layer 1       Layer 2         Layer 3
   Intents &   Accessibility  Screenshot      Shizuku
   native APIs   Service      (vision)        (optional)
        │            │             │               │
        └────────────┴──────┬──────┴───────────────┘
                            ▼
                    result / new UI state
                            │
                     back to the AI
```

### Layer 0 — Intents and native APIs *(already in use)*

**Use for anything Android exposes directly.** Deterministic, no special
permission, unaffected by UI redesigns, unaffected by the Play policy problem.

Already implemented: `DeviceClockToolExecutor` → `AlarmClock` intents.

Extend with: open app (`getLaunchIntentForPackage`), open a Settings page
(`Settings.ACTION_*`), dial, pre-filled SMS, share sheet, open URL, navigation.

### Layer 1 — AccessibilityService *(the main new work)*

For anything with no intent. Direct equivalents of the ADB verbs:

| ADB (proven) | AccessibilityService equivalent | Notes |
| --- | --- | --- |
| `uiautomator dump` | `rootInActiveWindow` → `AccessibilityNodeInfo` tree | Walk recursively |
| find by `resource-id` | `findAccessibilityNodeInfosByViewId("pkg:id/name")` | Exact, preferred |
| find by text | `findAccessibilityNodeInfosByText("…")` | Fuzzier fallback |
| `input tap X Y` | `node.performAction(ACTION_CLICK)` | Semantic — no coordinates |
| tap arbitrary point | `dispatchGesture(GestureDescription)` | API 24+; use only when no node |
| `input text "…"` | `node.performAction(ACTION_SET_TEXT, bundle)` | |
| scroll | `ACTION_SCROLL_FORWARD` / `_BACKWARD` | |
| `keyevent HOME/BACK/RECENTS` | `performGlobalAction(GLOBAL_ACTION_HOME / _BACK / _RECENTS)` | |
| — | `GLOBAL_ACTION_NOTIFICATIONS`, `_LOCK_SCREEN` (API 28+) | |

**Clicking a node beats tapping a coordinate**, for the same reason
`uiautomator dump` beat hardcoded taps in §2.1: it survives layout shifts,
different screen sizes and density changes.

If a node reports `isClickable == false`, walk up to the nearest clickable
ancestor rather than falling back to a raw gesture.

### Layer 2 — Screenshot vision *(use sparingly)*

`AccessibilityService.takeScreenshot()` when the node tree is not enough.
Downscale before sending to the model; full-resolution frames are wasteful
(the raw device screenshots in testing were 1080×2340, ~900 KB each).

### Layer 3 — Shizuku *(optional, advanced)* — see §8

---

## 6. Fitting into the existing code

The plumbing already exists and should be reused verbatim in shape. The current
clock flow is:

```
backend/src/agent.ts        tool({ name: "manage_device_clock", … })
      │                       execute → context.emitClientToolRequest(name, args)
      ▼
backend  SSE                emits a client-tool event
      ▼
ChatApi.kt                  ChatStreamEvent.ClientToolRequested(toolName, argumentsJson)
      ▼
ChatScreen.kt (~line 300)   DeviceClockToolExecutor.execute(context, toolName, argumentsJson)
      ▼
DeviceClockToolExecutor.kt  maps name → AlarmClock Intent → startActivity
```

**Add a parallel executor, do not modify the clock one:**

```
PhoneControlToolExecutor.kt   maps name → Layer 0/1/2 call → returns a result
```

### Files to touch

| File | Change |
| --- | --- |
| `backend/src/agent.ts` | Define the new tools (mirror `manageDeviceClock` shape); add to the `tools:` array; extend the agent `instructions` |
| `ChatApi.kt` | **Probably no change** — `ClientToolRequested` is already generic over `toolName`/`argumentsJson` |
| `ChatScreen.kt` | Route unknown tool names to the new executor |
| `PhoneControlToolExecutor.kt` | **New.** Validate args, dispatch to the right layer |
| `AssistantAccessibilityService.kt` | **New.** The service itself |
| `res/xml/accessibility_service_config.xml` | **New.** Service capabilities declaration |
| `AndroidManifest.xml` | Register the service with `BIND_ACCESSIBILITY_SERVICE` |

### One gap to close

The clock tools are **fire-and-forget** — the executor returns `Result<Unit>` and
the model never learns what happened. UI automation *needs a result channel*:
the model must know whether a tap landed, and what the screen looks like now.

So this feature requires a **new path back to the AI**: tool result → next turn.
Plan for it up front; retrofitting it later means reworking the streaming
protocol. This is the single biggest architectural difference from the existing
clock tools.

---

## 7. Proposed tool catalogue

Start small. Each tool is validated by `PhoneControlToolExecutor` before it runs.

**Observation (safe, read-only)**

| Tool | Args | Returns |
| --- | --- | --- |
| `get_screen_elements` | `{ interactiveOnly?: bool }` | Filtered node list: text, viewId, bounds, actions |
| `get_current_app` | — | Foreground package + activity |
| `take_screenshot` | — | Downscaled image |

**Navigation (low risk, reversible)**

| Tool | Args |
| --- | --- |
| `open_app` | `{ packageName }` |
| `press_home` / `press_back` / `press_recents` | — |
| `open_settings_page` | `{ page }` |

**Interaction (higher risk — these change state)**

| Tool | Args |
| --- | --- |
| `tap_element` | `{ viewId? , text?, contentDesc? }` — at least one required |
| `type_text` | `{ text, viewId? }` |
| `scroll` | `{ direction, viewId? }` |

Deliberately **not** in v1: `tap_coordinates` (brittle, bypasses semantics),
anything that sends a message or spends money, anything touching another app's
private data.

---

## 8. Shizuku

### What it is

Shizuku lets a normal app execute code **as the `shell` user (uid 2000)** — the
exact privilege level ADB has. It works by having the user start a Shizuku
service once via ADB (USB or **wireless debugging**, Android 11+), after which
apps can call system APIs through Shizuku's binder interface.

If adopted, it would give the app genuine `pm`, `am` and `input`-level power
without a PC and without root.

### The blocking limitation

**On a non-rooted device the Shizuku service must be restarted after every
reboot.** Pairing persists; the running service does not. Shizuku 13.6.0+ added
automatic start on Android 13+ over trusted Wi-Fi, but that is conditional, and
this project's test device is **Android 11**, where it does not apply.
**[VERIFIED from Shizuku docs/community; not tested on device.]**

So the realistic user experience on Android 11 is: *"after every restart, open
Shizuku and re-pair before your assistant can control the phone."* That is fine
for a personal power-user tool and unacceptable for a general product.

### Verdict

**Do not build v1 on Shizuku.** Build on Accessibility, which needs a one-time
grant that survives reboots.

Keep Shizuku as an **optional Layer 3** behind the same tool interface, for
capabilities Accessibility genuinely cannot reach — force-stopping apps,
granting permissions, `pm` operations, reading system state. Because the AI only
ever emits structured tool calls, adding Shizuku later changes only the router,
not the model-facing contract.

---

## 9. Security design

The external proposal's instinct — no raw shell for the model — is right.
Concretely:

1. **Whitelist, never passthrough.** The model picks a tool name from a fixed
   set. It never supplies a command string. An unknown tool name is an error,
   not a fallback.
2. **Validate every argument in the executor.** Package names against an allow
   list; text length capped; `viewId` must resolve to a real node.
3. **Tier the actions.** Read-only observation can run freely. State-changing
   interaction should be confirmable. Destructive or outward-facing actions
   (sending a message, a payment, a deletion) should require **explicit user
   confirmation in the UI**, every time.
4. **Never auto-drive sensitive surfaces.** Refuse to operate inside banking
   apps, password managers, or system permission dialogs. `FLAG_SECURE` windows
   are already invisible to both the node tree and `takeScreenshot()` — treat
   that as a floor, not a ceiling.
5. **Audit log.** Record every executed action locally with timestamp, tool,
   arguments and outcome, and make it viewable. This is both a debugging tool
   and the user's assurance about what the assistant did.
6. **Kill switch.** A single, always-reachable "stop automation" control.

> **Threat model note.** An AccessibilityService can read everything on screen in
> every app. That is a large amount of trust for a user to place in this app, and
> a large blast radius if the app is ever compromised or the model is prompt-
> injected by on-screen content. Treat text read from the screen as **untrusted
> input**, never as instructions.

---

## 10. Google Play policy — read this before building

**This is the finding that most constrains the design, and the external proposal
does not mention it.**

Google's AccessibilityService policy states that:

> *"Any use of the Accessibility API that enables an app to autonomously
> initiate, plan, and execute actions or decisions is strictly prohibited.
> However, this does not prohibit deterministic, rule-based automation, where
> behavior follows a static, human-defined script."*

And the exemption for `isAccessibilityTool="true"` explicitly **excludes this
category of app**:

> *"Examples of apps that are not accessibility tools are: antivirus software,
> **automation tools**, **assistants**, monitoring apps, cleaners, password
> managers, and launchers."*

Enforcement tightened as of **28 January 2026**.

### What this means concretely

| Use | Status |
| --- | --- |
| Personal / sideloaded build for yourself | ✅ Fine. No policy applies. |
| Distributed on Google Play as an AI assistant that plans and executes UI actions | ❌ Squarely in the prohibited category |
| Distributed on Play with *deterministic, user-defined* macros (no AI planning) | ⚠️ Possibly allowed — "if X then Y" is explicitly carved out |

An LLM choosing which button to press **is** autonomous planning. That is the
prohibited behaviour, and "assistants" cannot claim the accessibility-tool
exemption.

### Recommendation

Build it — for personal use, sideloaded. It is a genuinely useful assistant and
the policy does not restrict what you install on your own device. But **decide
now** whether Play distribution is a goal, because if it is, the architecture
has to change fundamentally (deterministic user-authored macros, with the AI
restricted to *suggesting* rather than *executing*).

Also note: heavy Accessibility use can conflict with Android's Advanced
Protection / enterprise-managed profiles, which may disable such services
entirely. **[UNVERIFIED]**

---

## 11. Development workflow

The external proposal's "prototype with ADB → implement in app" is right, and
was validated in practice. Refined:

1. **Explore over ADB.** `uiautomator dump` the target screen. Read the real
   `resource-id`s, `text`, `content-desc` and `bounds`.
2. **Confirm Android allows the action at all.** If ADB cannot do it as `shell`,
   an app certainly cannot.
3. **Decide the layer.** Is there an intent? → Layer 0. Otherwise → Layer 1.
4. **Implement behind a tool name**, then test in-app.
5. **Verify by state, not by screenshot.** `dumpsys window | grep mCurrentFocus`,
   node text, service records — the same checks that caught the Home-button bug.

**Caveat learned the hard way:** ADB proves *what Android permits*, not *what
your app may do*. `input tap` works over ADB and is permanently unavailable to
an app. Always re-check the permission level before assuming a prototype
translates.

---

## 12. Known limitations and gotchas

- **The user must enable the service manually** in Settings → Accessibility.
  It cannot be granted programmatically, by design. Expect drop-off here; write
  a good onboarding screen and deep-link with
  `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Some OEMs kill accessibility services aggressively.** ColorOS/MIUI/EMUI
  battery optimisation is a known cause. The test device is ColorOS — expect to
  need a battery-optimisation exemption prompt.
- **`FLAG_SECURE` windows are invisible** — no nodes, no screenshot. Banking
  apps, password fields, DRM video. This is deliberate and unbypassable.
- **Node quality varies.** Views and Compose expose reasonable semantics.
  Flutter needs semantics explicitly enabled; WebView and canvas-drawn UIs may
  expose almost nothing. This is where Layer 2 screenshots earn their place.
- **Node trees can be large.** Filter to interactive/labelled nodes before
  sending to the model, or the prompt will be enormous and slow.
- **Timing.** UI needs time to settle after an action. ADB testing used ~0.6 s
  between taps. In-app, prefer waiting on
  `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` events over fixed
  sleeps.
- **Screenshot rate limit** ~333 ms; do not build a fast capture loop.
- **`takeScreenshot()` is silent** — no notification, no indicator. Ethically
  this makes an in-app indicator more important, not less.

---

## 13. Open decisions for the implementer

Not decided yet; make these consciously.

1. **Is Google Play distribution a goal?** (§10) — changes everything.
2. **How do tool results get back to the AI?** (§6) — needs a protocol change.
3. **Does the model see node trees, screenshots, or both?** Recommendation:
   node tree by default, screenshot on demand as an explicit tool.
4. **Confirmation policy** — which tiers require a user tap before executing?
5. **Is Shizuku in scope at all**, given the reboot problem on Android 11?
6. **Where does the loop terminate?** An AI driving a UI can retry forever. Cap
   the steps per request and always allow interruption.

---

## 14. Summary

- The ADB experiment **proved the interaction model works**: observe → act →
  verify, reading structured UI data rather than guessing coordinates. **[VERIFIED]**
- ADB itself **can never ship in the app** — it is a `shell`-uid privilege. §3
- The external proposal is **directionally right**: structured tool calls, a
  whitelist, Accessibility as the engine, intents preferred, ADB for prototyping.
- Two corrections to it: use **`AccessibilityService.takeScreenshot()` rather
  than MediaProjection** (simpler, silent, and available on our minSdk), and
  **read the node tree before reaching for pixels**.
- **Shizuku is not viable for v1** because of the per-reboot restart on
  non-rooted Android 11. Keep it as an optional layer.
- **The Play policy makes this personal-use software** unless the design drops
  AI-driven planning. Decide that early.
- The existing `manage_device_clock` path is the right template — the one thing
  it lacks, and this feature requires, is a **result channel back to the model**.
