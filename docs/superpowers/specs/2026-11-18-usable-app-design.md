# Usable App Design: Honest Screens and Working Calls (Specs A and B)

Date: 2026-11-18. Owner direction: "a WhatsApp-like, actually useful UI and features", built by agents, specs written by the lead agent.

Background and evidence: [feature comparison](2026-11-18-feature-comparison.md) and the [inventories](../research/2026-11-18-inventories/).

## 1. Intent

Two people with our app on real phones can chat and call the way they would in any everyday messenger, and nothing on screen is fake. Today the engine supports most of this, but the screens do not call it, and several screens show invented information.

This design covers two specs:

- **Spec A, Honest and connected:** remove everything fake; connect the screens to features the stores already have.
- **Spec B, Calls with sound and picture:** link the media engine on iPhone and Mac; show video on all platforms.

Later specs (not here): C chat polish, D profile and finding people, E groups, F reach.

## 2. Goals, non-goals, anti-goals

**Goals**
- Every control on every screen either works on a live account or is not shown.
- A message can be replied to, copied, reacted to, edited, deleted, and forwarded.
- All history in the local database is reachable by scrolling.
- Received photos display; files open; voice messages play.
- The user always knows whether the app is connected.
- A call carries audio; a video call shows both pictures.
- iPhone, Mac, and Android behave the same unless a platform convention says otherwise.

**Non-goals (later specs)**
- Groups, typing indicators, last seen, pinned chats, archive, per-chat mute, swipe actions on the chat list.
- Hold-to-record voice notes, send preview with caption, video messages, media gallery, link previews.
- Profile photos, profile editing, contact QR codes, per-user invite links, app lock, backup.
- iPad, tablets, landscape, translations.

**Anti-goals**
- No new server endpoints or protocol messages. If a task seems to need one, stop and report.
- No new dependencies (project rule).
- No sample or placeholder data reachable from a live account.
- No change to cryptography, key handling, or MLS code.
- Not a copy of WhatsApp's look. Platform system components only.

## 3. Constraints

- Project rules in [AGENTS.md](../../../AGENTS.md): system components, SF Symbols / Material icons, system text styles, a VoiceOver or TalkBack label on every control, all strings in the String Catalog (Apple) or `strings.xml` (Android), no encryption labels outside chat info, plain words, brand placeholder `[App]`.
- Apple app targets build only in GitHub Actions on Mac runners. An Apple task is done only with a green run.
- Android builds and emulator tests run locally.
- Sample mode (`-sampleData` on Apple, the `sampleData` intent extra on Android) stays for UI tests only. Sample types may be used by tests; live code paths must not call them.
- Tinode's Apache-2.0 app code may be studied and adapted with credit. SimpleX is AGPL: behaviour only, no code.

## 4. Approaches considered

1. **Wire the existing screens to the existing stores (chosen).** Smallest change, uses tested engine code, fastest route to a usable app.
2. **Fork Tinode's apps and replace their network layer.** Rejected: UIKit and Java against our SwiftUI and Compose, no end-to-end encryption, and most of the code would be rewritten anyway.
3. **Redesign and rebuild the UI from new mockups first.** Rejected for now: the app would stay unusable for weeks. A full design pass comes with Spec C, on top of a working app.

## 5. Spec A: behaviour

Behaviour is the same on iPhone, Mac, and Android unless noted. "Store" means `ChatStore` (`apps/apple/Packages/AppCore/Sources/AppCore/Chats/ChatStore.swift`, `apps/android/core/src/main/kotlin/org/example/app/core/chats/ChatStore.kt`).

### 5.1 Truth on screen

| Place | Today | Required |
|---|---|---|
| Chat list status strip | Always a green dot | Bound to the live connection: green dot and host when connected; grey dot and "Connecting…" while connecting or waiting to retry; grey dot and "No connection" when the device has no network |
| Settings > Server | Always "Connected" | Same three states as the strip |
| Chat header subtitle | "online" for everyone (Apple) | No subtitle. (Presence comes in Spec C.) |
| Add contact > Scan QR code (Apple) | Adds sample user "neha" | Button removed. (Contact QR comes in Spec D.) |
| Contacts > Invite people (Apple) | Shares a sample invite code | Row hidden on live accounts, as Android already does |
| Composer > Location (Apple) | Sends the text "📍 Shared location" | Removed |
| Composer > Camera | Apple: nothing on live. Android: nothing | Takes a photo with the system camera and sends it (see 5.4) |
| Settings > Data & Storage usage and storage figures (Apple) | Invented numbers | Section removed |
| Settings > Notifications toggles (Apple) | Stored, never read | Replaced by one row, "Notification settings", that opens the system settings page for the app |
| Settings > Notifications delivery choice (Android) | Radio that changes nothing | Read-only row showing the transport actually in use |
| Privacy > Find me by username | Local only | Removed |
| Privacy > Find me by email | Local only | Sent to the server with the existing `updateSettings(findByEmail:)` call; reverts with an error message if the call fails |
| Privacy > Disappearing messages; chat info > Disappearing messages | No effect | Both removed |
| Chat info > Mute | Flips an icon | Removed |
| Chat info > Search | Apple: runs chat-list search. Android: nothing | Opens in-chat search (5.3) |
| Help text about backups (Apple) | Mentions a backup that does not exist | Says a new device starts with empty history unless it is linked from an existing device |

Connection state has one shared type per platform:

- Apple, in AppCore: `public enum ConnectionStatus: Equatable, Sendable { case connected, connecting, offline }`. `AppModel` exposes `var connectionStatus: ConnectionStatus`, mapped from `ServerKit.ConnectionState` and the path monitor.
- Android, in `core`: `enum class ConnectionStatus { Connected, Connecting, Offline }`, exposed by the graph as `val connectionStatus: StateFlow<ConnectionStatus>`.
- Sample mode reports connected.

Strings: "Connecting…", "No connection", "Connected to %@" (accessibility label, existing).

### 5.2 Message actions

Long-press a bubble (secondary click on Mac) opens the platform context menu. Items, in this order, each shown only when it applies:

| Item | Shown for | Result |
|---|---|---|
| Reply | Any message that is not deleted, a system line, or a call record | A reply bar appears above the composer with the sender name, a one-line preview, and a close button. Sending attaches `replyTo`. |
| React | Same as Reply | A row of six emoji (👍 ❤️ 😂 😮 😢 🙏) at the top of the menu. Tapping one calls `react`. Tapping the same one again removes it, if the store's `react` toggles; otherwise re-reacting replaces. Follow the store. |
| Copy | Text messages | Copies the text. |
| Forward | Text messages | Opens a chat picker (the existing new-chat contact list); choosing a contact sends the same text to that chat and opens it. Media forwarding is Spec C. |
| Edit | Own text messages that are not failed | The composer fills with the text and shows an "Editing" bar with a close button; the send button calls `edit`. |
| Delete | Own messages | Confirmation "Delete this message for everyone?" with Delete and Cancel; calls `delete`. |

Other required behaviour:
- Tapping a quote inside a bubble scrolls to the original message and briefly highlights it. If the original is not loaded, load older pages until it is found or history ends.
- Edited messages show "edited" next to the time (Android already does).
- On Apple, "delivered" and "read" must look different: delivered is an outline check circle, read is a filled one in the accent colour.
- Android gains `DeliveryStatus.Failed` in `core`, mapped from the outbox's failed state; the bubble shows "Not sent" and "Try again", which calls a new `ChatStore.retrySend(messageId: String)`. Apple already has this.
- Text in bubbles detects links; tapping one opens the system browser.

Strings: "Reply", "Copy", "Forward", "Edit", "Delete", "Editing", "Replying to %@", "Delete this message for everyone?", "edited", "Not sent", "Try again", "Forward to…".

### 5.3 History and navigation in a chat

- **Older messages.** The chat shows the cached newest messages first. When the user scrolls within five messages of the oldest loaded one, the screen calls `messages(in:before:limit:)` with a limit of 50 and prepends the result without moving the scroll position. Stop asking when a call returns fewer than 50.
- **Date separators.** A centred chip above the first message of each local day: "Today", "Yesterday", or the system medium date.
- **Unread marker.** When a chat is opened with unread messages, a line "Unread messages" appears above the first unread message and the chat opens scrolled to it. The marker stays until the chat is closed.
- **Scroll to bottom.** A round button appears at the bottom trailing edge when the newest message is off screen; it shows the count of messages that arrived since, and scrolls to the newest when tapped.
- **New messages.** If the user is at the bottom, the chat follows new messages. If not, it stays put and the button count rises.
- **In-chat search.** Chat info > Search opens a search field over the chat. Results come from `searchMessages`, filtered to this chat, as a list of previews with dates; tapping one scrolls to that message (loading older pages as needed) and highlights it.
- **Android chat-list search.** A search action in the chat list top bar opens a search screen: matching contacts by name or username first, then message hits from `searchMessages`; tapping opens the chat, and for a message hit scrolls to it.
- **Drafts.** Unsent composer text is kept per chat for the life of the process (a dictionary in the model), so leaving a chat and coming back keeps it. Persisting across restart is Spec C.
- **Empty states.** Empty chat list: "No chats yet" with a "New chat" button. Empty chat: "No messages yet". Empty contacts: "No contacts yet" with "Add contact".

Strings: "Today", "Yesterday", "Unread messages", "Scroll to newest", "Search in chat", "No results", "No chats yet", "New chat", "No messages yet", "No contacts yet".

### 5.4 Media

- **Photos.** A photo bubble shows the real image once its `media.phase` is local. Before that it shows the size and a download button that calls the store's `download`; while downloading it shows progress from the phase. A failed download shows "Couldn't download" and a retry button. Tapping a local photo opens it full screen with pinch zoom and a share button (Apple: Quick Look; Android: a full-screen composable with the system share sheet). Decode images sampled to the display size; never load a full-resolution bitmap into a bubble.
- **Files.** A file bubble shows name and size, the same download states, and when local opens with the system viewer (Apple: Quick Look; Android: `ACTION_VIEW` through a `FileProvider`). A share button is offered.
- **Voice messages.** The play button plays the message; it becomes pause while playing; a progress bar and elapsed time replace the static waveform; only one voice message plays at a time; playback stops when the chat closes. Apple must play the Ogg Opus files the app already records and receives using code already in the repository (the `OggOpus` code in `Messaging` and system audio frameworks). If that cannot be done without a new dependency, stop and report.
- **Sending on Android.** The attach sheet's Photo opens the system photo picker, File opens the system document picker, Camera takes a photo with the system camera; each calls `sendAttachment`. The microphone button records with the existing `VoiceRecorder`: tap to start, a recording bar shows elapsed time with Cancel and Send. Microphone and camera permissions are requested when first needed, with the system prompt.
- **Camera on Apple.** iPhone: the system camera, then `sendAttachment`. Mac: the Camera item is not shown.
- **Upload state.** An outgoing attachment shows "Sending…" with the clock mark until sent, and "Not sent" with "Try again" when failed.

Strings: "Couldn't download", "Download", "Share", "Play voice message", "Pause", "Recording…", "Cancel", "Send", "Sending…".

### 5.5 Account and contacts

- **Sign out.** Settings > profile > "Sign out", with confirmation "Sign out of this device? Your messages on this device will be removed." It removes this device from the account (the existing device-revoke call used by Linked devices), wipes local data, and returns to the welcome screen.
- **Delete account on Android.** Settings > profile > "Delete account" with the same confirmation text as Apple, calling the existing `deleteAccount()`.
- **Unblock.** Privacy > Blocked shows names (falling back to the username, then the ID) and an "Unblock" action per row. If the store has no unblock call, add `unblock(contactID:)` using the server's existing unblock endpoint; if the server has none, stop and report.
- **Message requests on Android.** A request chat shows the Accept / Block / Report bar in place of the composer, as Apple does, calling `accept`, `block`, `report`.
- **New chat on Android.** The new-chat button opens a contact picker with an "Add contact" row; choosing a contact opens the chat (`ensureChat`).
- **New chat on Mac.** A "New chat" toolbar button opening the same picker as iPhone.
- **Settings persistence on Android.** `SettingsStore` loads from and saves to disk (SharedPreferences, since no new dependency is allowed), so settings survive restart.
- **Notification tap on Android.** Tapping a message notification opens that chat (`MainActivity` reads `EXTRA_CHAT_ID`, including when the app is already running).

Strings: "Sign out", "Sign out of this device? Your messages on this device will be removed.", "Unblock", "Add contact".

## 6. Spec B: behaviour

- **iPhone and Mac media.** The app targets link the `CallMedia` package (and through it `WebRTCBinary`). `LiveMedia.make()` returns `CallMedia`'s WebRTC media session. `UnavailableMediaSession` is deleted. `MediaCapabilities.enhancedNoiseSuppression` becomes true only if the capture hook it depends on is present in the released binary; otherwise leave it false and say so in the report.
- **Video picture.** The call screen shows the remote video full screen and the local video in a small corner tile, replacing the placeholder, on iPhone, Mac, and Android. Before the first remote frame, the contact's avatar is shown. When the remote side turns video off, the avatar returns.
- **Flip camera.** Works on iPhone and Android; hidden on Mac and on devices with one camera.
- **Camera permission.** Requested when the user first starts or accepts a video call or turns video on. If refused, the call continues as audio and a one-line message says "Camera access is off. Turn it on in Settings."
- **Network changes on Android.** A `ConnectivityManager.NetworkCallback` reports network changes to the call engine, so moving between Wi-Fi and mobile data triggers the existing reconnect logic at once.
- **Failure text.** If media cannot start, the call ends with "Call failed" (existing) and the reason is logged; no silent hang.

Spec B changes how the WebRTC binary is linked but not the binary itself, the Opus build, or any cryptography. It still gets the security reviewer.

Not in B: group calls, screen sharing, picture-in-picture, real-device audio quality tests (owner's call lab task L10).

## 7. Testing

- **Logic tests** for everything decidable without a UI: connection status mapping, menu item rules (which actions apply to which message), date separator grouping, unread marker position, paging state (when to ask, when to stop), draft keeping, media bubble state mapping, settings persistence, notification intent parsing.
- **Apple:** logic in `AppCore` so it runs locally with `swift test`; UI tests in `AppUITests` on sample data for the menu, reply bar, edit bar, scroll button, and search; green GitHub Actions run required.
- **Android:** unit tests plus emulator tests for the same flows, run locally.
- **Live check, both platforms:** each lane's report must state what was and was not verified against a live server. Anything not verified live is listed for the owner's device check.

## 8. Decided here vs left to the builder

**Decided:** every behaviour, string, and type name above; the six reaction emoji; page size 50; menu order.

**Left to the builder:** view and file structure, animation details, exact spacing, how the highlight flash looks, which system API implements each system-component behaviour.

**Lead agent's calls (not owner decisions):** removing dead settings instead of building them now; drafts kept in memory only; forwarding limited to text; six fixed reactions.

## 9. Open items for the owner

- Signed iPhone build, push key, a server on a real domain. Without these the app cannot be used outside development.
- Real-device checks listed by each lane's report.
