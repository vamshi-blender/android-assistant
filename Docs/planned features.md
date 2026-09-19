I reviewed the shipped JavaScript feature modules, the decompiled Android bridge, manifest, and tool registry. The APK contains **64 main agent tools, 47 connector tools, and 111 native bridge commands**, although some are internal plumbing, placeholders, or desktop/iOS leftovers.

The central lesson is that we should build a **tool execution platform**, then add WhatsApp, Uber, browser automation, calendar, and other capabilities as tools. Implementing each feature directly inside prompts will become unreliable quickly.

## What our app already has

Our project already covers:

- Text chat with streaming responses
- Live voice conversations
- Web search
- Alarms and timers
- Offline wake-word detection
- Overlay UI
- Default-assistant and power-button activation
- Lock-screen assistant activity

Our system-level activation is actually stronger than this ConscioussAI APK, which has no Android `VoiceInteractionService`.

## Capabilities to implement

| Area | Capability | What ConscioussAI actually does | Recommended priority |
|---|---|---|---|
| Messaging | WhatsApp, Telegram and SMS | Resolves contacts and opens a prefilled composer; user taps Send | High |
| Rides | Uber and Lyft | Resolves coordinates and opens the ride app with pickup/drop-off filled | High |
| Maps | Directions and geocoding | Uses device location, Android geocoder and Google Maps links | High |
| Device control | Volume, brightness, flashlight, DND | Uses native Android APIs when permitted | High |
| App control | Open applications and specific screens | Uses custom schemes, universal links and intents | High |
| Contacts | Find people by name | Reads contacts locally, including fuzzy and phonetic matching | High |
| Calendar | Create/list/update events | Uses a native compose intent or connected Google/Outlook APIs | High |
| Browser agent | Navigate and operate websites | Controls an embedded WebView through DOM and screenshot tools | High |
| Email | Gmail and Outlook | Reads and sends through authenticated cloud APIs with approval | Medium |
| Media | Spotify, YouTube Music and others | Searches for media and opens provider-specific deep links | Medium |
| Attachments | Images, PDFs and Office files | Picks and parses documents for AI context | Medium |
| Screen sharing | Real-time screen understanding | Uses MediaProjection and sends frames to a live vision model | Medium |
| Memory | Personal facts across chats | Extracts durable facts and injects relevant ones into later chats | Medium |
| Reminders | Scheduled follow-ups | Schedules local notifications and opens the relevant conversation | Medium |
| Commerce | Shopping, food and hotels | Automates supported websites inside its WebView | Later |
| Connectors | Slack, Notion, LinkedIn, Gmail | Uses a third-party connector service | Later |
| Documents | PDFs, presentations and spreadsheets | Creates PDFs locally; presentations and spreadsheets use cloud services | Later |
| SOL | Communicate with the assistant by SMS | Pairs a phone number with a remote gateway | Later |

## 1. Messaging tools

We should implement:

```text
lookup_contact(name)
compose_message(app, recipient, text)
share_image(app, recipient?, caption)
open_conversation(app, recipient)
```

Supported destinations should initially be:

- WhatsApp
- Telegram
- Android Messages/SMS

WhatsApp behavior:

1. Resolve “Mom” using Android contacts.
2. Normalize the phone number to international format.
3. Show an editable message preview.
4. Open:

```text
whatsapp://send?phone=<number>&text=<message>
```

5. Return `OPENED_FOR_USER`, because the user must tap Send.

Image sharing should open Android’s share sheet. We cannot reliably select the WhatsApp recipient or press Send.

The contact resolver is worth copying conceptually: it tries exact matches, nicknames, fuzzy spelling and phonetic similarity, and asks the user when multiple contacts match.

## 2. Uber and Lyft

ConscioussAI’s “book ride” feature does not book a ride. It:

1. Gets the current device location.
2. Geocodes the destination.
3. Optionally geocodes a custom pickup location.
4. Builds a deep link containing pickup and destination coordinates.
5. Opens Uber or Lyft.
6. Leaves ride type, fare review and booking confirmation to the user.

For Uber:

```text
uber://?action=setPickup
&pickup[latitude]=...
&pickup[longitude]=...
&dropoff[latitude]=...
&dropoff[longitude]=...
&dropoff[nickname]=...
```

It falls back to an Uber web link when the app is unavailable.

We should implement:

```text
get_device_location()
geocode_place(query)
open_directions(destination, travelMode)
prepare_ride(provider, pickup?, destination)
```

The result should say:

> Uber opened with your pickup and destination filled. Review the fare and confirm the ride in Uber.

It should never claim that the ride was booked.

## 3. General app and deep-link router

ConscioussAI has a large catalog of schemes and universal links. This allows requests such as:

- Open Instagram
- Search for a song in Spotify
- Open a YouTube video
- Open an Amazon product
- Navigate to a destination in Maps
- Open a WhatsApp conversation

We should centralize this:

```kotlin
interface AppActionHandler {
    fun canHandle(action: AppAction): Boolean
    suspend fun execute(action: AppAction): ToolResult
}
```

The router should:

1. Try a native intent or verified custom scheme.
2. Try an HTTPS app link.
3. Open the web page as a fallback.
4. Return how far it got.

## 4. Native device controls

The APK contains tools for:

- Get/set brightness
- Auto-brightness
- Volume up/down/set/get
- Mute/unmute
- Flashlight on/off
- Do Not Disturb
- Battery state
- Screen orientation
- Keep screen awake
- Dark/light application theme
- Alarm and timer
- Stopwatch
- Wi-Fi and Bluetooth settings
- Battery-saver settings
- Airplane-mode settings
- Mobile-data settings
- Voice-recorder handoff

On modern Android, Wi-Fi, Bluetooth, airplane mode, mobile data and battery saver generally cannot be changed silently by an ordinary app. We should open the appropriate settings panel and return `OPENED_FOR_USER`.

## 5. Embedded browser agent

This is the largest missing capability.

ConscioussAI embeds a WebView and exposes tools such as:

```text
go_to_url
read_page
click_element
click_text
input_text
scroll
go_back
wait_for_text
tap_at
type_at
take_screenshot
```

Its agent loop follows this pattern:

```mermaid
flowchart LR
    A[Read WebView DOM] --> B[Send page state to model]
    B --> C[Model selects one tool]
    C --> D[Execute inside WebView]
    D --> E[Observe result]
    E --> B
```

It prefers DOM operations and uses screenshot coordinates when DOM controls fail. Runs are bounded to prevent infinite loops and excessive model usage.

This single feature enables:

- Shopping research
- Adding products to carts
- Food-order preparation
- Hotel searches
- Form filling
- Checkout preparation
- Website sign-in handoffs
- Browser-based workflows

The browser should have:

- A domain allow/block policy
- Navigation history
- Cookie and session support
- Stop/cancel controls
- Tool-call timeouts
- Maximum step count
- Login and payment interruption
- Protection against prompt injection from webpages
- Accurate completion detection

## 6. Shopping, food and travel

The shipped code has site-specific handling for:

- Amazon
- Walmart
- Croma
- eBay
- Flipkart
- Swiggy
- DoorDash
- Costco
- Best Buy
- H&M
- Target
- Etsy
- Instacart
- Blinkit
- Myntra
- Booking.com

These are browser workflows, not separate Android integrations.

It can search stores, identify products, check availability, handle required food options, add items to carts and navigate hotel room-selection pages. Login, payment, final order placement and sensitive personal details should interrupt the agent for the user.

I would implement this only after the generic browser agent works. Site-specific scripts are expensive to maintain because websites change constantly.

## 7. Calendar and reminders

There are two calendar paths:

- Native Android calendar compose intent: opens an event form for the user.
- Google/Outlook APIs: list, create, update and delete events after OAuth and confirmation.

We should implement:

```text
open_calendar_event_draft
list_calendar_events
create_calendar_event
update_calendar_event
delete_calendar_event
find_free_time
schedule_local_reminder
```

For the first release, the native compose intent and local notifications are enough. Connected calendar APIs can follow.

The Consciouss APK’s simple `createReminder` uses an in-process delayed handler, which can be lost if Android kills the app. We should use `AlarmManager` or `WorkManager` depending on timing precision.

## 8. Email and connected applications

The package contains real tool declarations for:

### Gmail

- Read emails
- Send emails
- Get account profile
- Create/list/get/delete drafts
- Create/list/apply labels
- Trash and restore messages

### Slack

- Read channels
- Send and schedule messages
- List users and members
- Create/archive/unarchive channels
- Invite/remove users
- Add reactions
- Set DND
- Work with Slack Lists

### Notion

- Search pages
- Create pages
- List users
- Get user information

### LinkedIn

- Create/delete posts
- Create comments
- Look up people and companies
- Read profile and network information

### Other

- Outlook email search
- Instacart retailer search and recipe links
- Google Calendar
- Google Drive backup

These depend on its remote proxy and connector service. The APK alone does not contain their server implementations.

For our app, start with direct Google OAuth for Gmail and Calendar. Add a generic connector abstraction later rather than embedding dozens of provider-specific APIs into the Android app.

## 9. Media control

ConscioussAI can search and open:

- Spotify
- YouTube
- YouTube Music
- JioSaavn
- Amazon Music
- SoundCloud
- Tidal
- Gaana
- Wynk

This is mostly search plus deep links. It does not generally control playback through Android media sessions.

We can implement a useful first version with:

```text
search_media(query, provider?)
open_media_result(provider, url)
media_play_pause()
media_next()
media_previous()
```

The last three can use Android’s active media-session controls where available.

## 10. Screen sharing and live vision

The app uses Android MediaProjection to capture the whole screen after explicit system consent. Frames are sent to a live model, allowing the user to ask questions about another application.

We should treat these as separate capabilities:

- `screen_observation`: understand another app and provide instructions.
- `browser_takeover`: operate only our embedded browser.
- `cross_app_control`: unavailable unless we deliberately add AccessibilityService.

The Consciouss APK does not provide general cross-app control. MediaProjection can see the screen but cannot tap it.

## 11. Attachments and document tools

The app accepts up to three files per turn and handles:

- Images
- Camera photos
- PDFs
- DOCX
- XLSX
- PPTX
- CSV/TSV
- Text, JSON and code files

It parses much of this locally into structured text. It also includes:

- On-device PDF creation from HTML/CSS
- PDF text extraction
- PDF page rendering
- Presentation generation through a cloud service
- Spreadsheet generation and editing through a cloud service
- File save/open/share flows

For our app, image and PDF attachments should come first. Office formats and document creation can follow.

One advertised photo feature is not functional in this APK: native `photoLatest()` returns an empty object. We should use Android’s Photo Picker rather than reading the user’s latest photo implicitly.

## 12. Memory, chats and projects

ConscioussAI stores:

- Up to 50 conversations
- Up to 200 messages per conversation
- Up to 200 durable personal memories
- Up to 2,000 ambient events
- Project folders for related conversations
- Cross-conversation topic summaries
- Optional Google Drive backup
- Incognito conversations excluded from memory and backup

After conversations, it asks a model to extract durable facts such as preferences, relationships, goals and personal attributes. Relevant memories are inserted into later prompts.

We should implement:

1. Room database for conversations and messages.
2. Explicit project folders.
3. Optional memory extraction.
4. A memory management screen.
5. Incognito mode.
6. Export/delete controls.
7. Optional encrypted backup.

Using Room is preferable to the APK’s large `localStorage` JSON blobs.

## 13. Proactive features

ConscioussAI can:

- Parse explicit reminders from conversations.
- Decide whether a later check-in could be useful.
- Schedule local notifications.
- Open the relevant conversation from the notification.
- Accept a notification reply.
- Keep an active agent task alive through a foreground service.
- Send check-ins through SOL when paired.

Its mobile “ambient” mode only observes activity inside its own application: chats, browser URLs, pasted content and calendar snapshots. It does not monitor other Android apps.

Some native ambient methods are incomplete: status always reports active and native forget does nothing, although the JavaScript store removes events.

## 14. Product infrastructure

To reach feature parity, we would eventually need:

- User accounts and session management
- Google/Apple sign-in
- Multiple AI-model selection
- Feature flags
- Subscription and credit accounting
- Billing
- Usage limits
- Conversation sync
- Connector management
- Error reporting
- Onboarding and personality setup

These are product capabilities rather than assistant actions, but many cloud tools depend on them.

## The foundation we should build first

Every tool should return a typed result:

```kotlin
sealed interface ToolResult {
    data class Completed(val evidence: String) : ToolResult
    data class OpenedForUser(val instructions: String) : ToolResult
    data class PermissionRequired(val permission: String) : ToolResult
    data class ConfirmationRequired(val preview: ActionPreview) : ToolResult
    data class Failed(val reason: String) : ToolResult
    data class Unknown(val explanation: String) : ToolResult
}
```

We also need:

- Tool schemas shared between Android and backend
- Argument validation
- Permission broker
- Confirmation UI for sensitive actions
- Idempotency keys
- Cancellation and timeouts
- Execution audit history
- Evidence-based completion
- Tool availability based on device and installed apps

This prevents mistakes such as saying “WhatsApp message sent” when only the composer opened.

## Recommended implementation order

### Phase 1: Native everyday actions

- Contact lookup
- WhatsApp/Telegram/SMS compose
- Share image/text
- App and deep-link router
- Device location and geocoding
- Maps directions
- Uber/Lyft preparation
- Brightness, volume, flashlight and DND
- Calendar event drafts
- Local reminders
- Media deep links

### Phase 2: Browser agent

- Embedded WebView
- DOM extraction
- Click/type/scroll/navigation tools
- Screenshot fallback
- Bounded agent loop
- Login, payment and sensitive-action interruptions

This unlocks most commerce and travel cases.

### Phase 3: Connected accounts

- Authentication
- Google OAuth
- Gmail
- Google Calendar
- Drive backup
- Outlook
- Connector framework

### Phase 4: Personal assistant state

- Chat history
- Projects
- Long-term memory
- Incognito mode
- Attachments
- Proactive follow-ups
- Background task notifications

### Phase 5: Advanced workflows

- Screen sharing and live vision
- Shopping and food automation
- Hotel workflows
- Document generation
- Slack, Notion and LinkedIn
- SOL-style SMS gateway

I would not prioritize Alfred-style automated phone calls yet. That feature is advertised on the current website, but it is **not implemented in this 1.0.2 Android package**.

The detailed static-analysis evidence remains in [consciouss-apk-analysis.md](/D:/vamshi/projects/ai-assistant/Docs/consciouss-apk-analysis.md).