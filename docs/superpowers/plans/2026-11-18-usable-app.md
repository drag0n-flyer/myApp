# Usable App (Specs A and B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every control in the iPhone, Mac, and Android apps works on a live account or is gone, and calls carry audio and video.

**Architecture:** The stores already implement messaging features; this plan adds small, tested logic types in `AppCore` (Swift) and `core` (Kotlin) and connects the existing screens to the stores through them. No protocol, server, or cryptography changes.

**Tech Stack:** Swift 6 / SwiftUI (iOS 17+, macOS 14+), Kotlin / Jetpack Compose, the existing `ChatStore` on each platform, the project's WebRTC builds.

**Spec:** [docs/superpowers/specs/2026-11-18-usable-app-design.md](../specs/2026-11-18-usable-app-design.md). Read it in full before starting a task; section numbers below refer to it.

## Global Constraints

- No new dependencies. No new server endpoints or protocol messages. If a task seems to need either, stop and report.
- Do not change cryptography, key handling, MLS code, the push gateway, or the WebRTC/Opus build scripts.
- Apple system components and SF Symbols only; Material components on Android. System text styles.
- A VoiceOver label (Apple) or content description (Android) on every control.
- All user-visible strings in `apps/apple/App/Resources/Localizable.xcstrings` or `apps/android/app/src/main/res/values/strings.xml`, with the exact text from the spec.
- Never show "codec", "kbps", "relay", "MLS", or "E2EE" in the UI. No encryption labels outside chat info.
- Live code paths must not call `Sample*` or `Simulated*` types. Sample mode stays for UI tests.
- Commits: plain messages, author `drag0n-flyer`, no `Co-Authored-By` or other AI trailers.
- Work on the task's own branch in its own worktree. Do not merge to `main`; the lead merges after review.
- Heavy commands (`swift test`, any `./gradlew`) run under the machine-wide lock: `flock /tmp/myapp-heavy.lock <command>`. Check `free -h` and `df -h /home` first; stop if less than 6 GB RAM or 15 GB disk is free.
- Apple tasks: `AppCore` tests locally (`cd apps/apple/Packages/AppCore && flock /tmp/myapp-heavy.lock swift test`); app targets build only in GitHub Actions. Push the branch once per batch of work, watch with `gh run watch`, read failures with `gh run view --log-failed`, cancel superseded runs. A task is done only with a green run.
- Android tasks: unit tests with `cd apps/android && flock /tmp/myapp-heavy.lock ./gradlew testDebugUnitTest`; emulator tests locally under `sg kvm -c '...'` with `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (see `apps/android/README.md`), also under the lock. Run `./gradlew --stop` when finished.

## Review Focus

1. **A reply, quote jump, or search hit pointing at a message older than the loaded page.** Expected: older pages load until it is found; if history ends first, nothing crashes and the chat stays where it was. Pinned in Tasks 2 and 5.
2. **Messages arriving while the user is scrolled up or is loading an older page.** Expected: scroll position does not jump, no duplicates, the scroll button count rises. Pinned in Tasks 2 and 5.
3. **Acting on a message that changes underneath the menu** (deleted remotely, edit of a message that fails to send, reacting twice quickly). Expected: the action is dropped or applied once, never a crash or a stuck edit bar. Pinned in Tasks 2 and 5.
4. **A media file that is missing, truncated, or huge** (local URL no longer exists, zero bytes, 40-megapixel photo, 30-minute voice note). Expected: a "Couldn't download" or neutral placeholder, bounded memory, no crash. Pinned in Tasks 3 and 6.
5. **Connection flapping and permission refusal** (network drops during sign-out or account deletion, camera or microphone refused, permission revoked in system settings mid-call). Expected: a clear message, the action can be retried, a call continues as audio. Pinned in Tasks 1, 4, 7, and 8.

## File ownership

Tasks run in parallel in two waves. A task edits only the files it owns, plus the shared string file. String-file merge conflicts are expected and the lead resolves them.

| Task | Branch | Wave | Owns |
|---|---|---|---|
| 1 Apple: truth and account | `feat/a1-apple-truth` | 1 | `AppModel.swift` (connection, sign-out, unblock), `StatusStripView`, `SettingsView`, `PrivacyView`, `DataStorageView`, `AddContactView`, `ContactsView`, `ChatInfoView` (remove rows only), `ChatListView`, `MacRootView`, `NewChatView` |
| 2 Apple: chat screen | `feat/a2-apple-chat` | 1 | `ChatView`, `MessageBubbleView`, `ComposerView`, `ChatHeaderView`, new files under `App/Features/Chat/`, `ChatInfoView` (Search button only), new `AppModel+Chat.swift` |
| 3 Apple: media | `feat/a3-apple-media` | 2, after 2 merges | media parts of `MessageBubbleView`, `ComposerMedia`, `AppModel+Attachments`, new files under `App/Features/Media/` |
| 4 Android: truth, dead buttons, account | `feat/n1-android-truth` | 1 | `AppRoot.kt` (all but the `CHAT` block), `MainActivity`, `AppGraph`, `ui/chatlist/*`, `ui/contacts/*`, `ui/settings/*`, `ui/chatinfo/*` (remove rows only), new `ui/search/*`, `ui/requests/*` |
| 5 Android: chat screen | `feat/n2-android-chat` | 1 | `ui/chat/*`, the `CHAT` block of `AppRoot.kt`, `ui/chatinfo/*` (Search button only), `core` chat models, `messaging` status mapping |
| 6 Android: media | `feat/n3-android-media` | 2, after 5 merges | media parts of `ui/chat/*`, new `ui/media/*`, `AndroidManifest.xml` provider entry |
| 7 Apple: call media and video | `feat/b1-apple-calls` | 1 | `project.yml`, `App/Calls/MediaSeam.swift`, `App/Features/Calls/*`, `Info.plist` camera text |
| 8 Android: call video and network | `feat/b2-android-calls` | 1 | `ui/call/*`, `calls-android/*`, call wiring in `AppGraph` |

Tasks 1 and 4 and Tasks 2 and 5 are the same behaviour on two platforms; use the same type and function names (Swift camelCase members, Kotlin PascalCase enum cases).

---

### Task 1: Apple: truth on screen and account actions (spec 5.1, 5.5, empty states of 5.3)

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Account/ConnectionStatus.swift`
- Test: `apps/apple/Packages/AppCore/Tests/AppCoreTests/ConnectionStatusTests.swift`
- Modify: the files this task owns (table above); `apps/apple/AppUITests/*` where a removed control was asserted.

**Interfaces:**
- Produces: `public enum ConnectionStatus: Equatable, Sendable { case connected, connecting, offline }` with `public static func from(socketConnected: Bool, hasNetwork: Bool) -> ConnectionStatus` (no network gives `.offline`; network without socket gives `.connecting`; both gives `.connected`). `AppModel.connectionStatus: ConnectionStatus` (observable). `AppModel.signOut() async`, `AppModel.unblock(contactID: String)`.
- Consumes: `ServerKit.ConnectionState`, the existing path monitor, the device-revoke call used by `LinkedDevicesView`, `ServerClient.updateSettings(findByEmail:)`.

- [ ] **Step 1: Write failing tests** in `ConnectionStatusTests`: `from(socketConnected: true, hasNetwork: true) == .connected`; `from(false, true) == .connecting`; `from(false, false) == .offline`; `from(true, false) == .offline`.
- [ ] **Step 2: Run** `swift test --filter ConnectionStatusTests`; expect a compile failure.
- [ ] **Step 3: Implement `ConnectionStatus`.** Run again; expect pass. Commit.
- [ ] **Step 4: Bind the UI.** `AppModel.connectionStatus` updates from `setConnectionState` and the path monitor; sample mode is always `.connected`. `StatusStripView` takes `status: ConnectionStatus` and shows the three states of spec 5.1 with matching accessibility labels. Settings > Server shows the same. Commit.
- [ ] **Step 5: Remove fake and dead controls** exactly as the spec 5.1 table lists for Apple rows this task owns: Scan QR button, Invite row on live accounts, Data & Storage figures, Notifications toggles (replace with the "Notification settings" row opening `UIApplication.openNotificationSettingsURLString`; on Mac open System Settings' notifications pane), Find me by username, Disappearing messages (Privacy and chat info), chat info Mute, backup help text. Remove now-unused strings and `@AppStorage` keys. Commit.
- [ ] **Step 6: Find me by email** calls `updateSettings(findByEmail:)`; on failure the toggle reverts and an alert shows the existing generic error text. Commit.
- [ ] **Step 7: Sign out and unblock.** "Sign out" row and confirmation with the spec's exact text; `signOut()` revokes this device, then runs the same local wipe as account deletion; if the revoke call fails for lack of connection, show "No connection" and do not wipe. Blocked list shows display name, then username, then ID, with "Unblock". If neither store nor server has an unblock call, stop and report instead of inventing one. Commit.
- [ ] **Step 8: Empty states and Mac new chat.** Empty chat list ("No chats yet" + "New chat"), empty contacts ("No contacts yet" + "Add contact"), a "New chat" toolbar button in `MacRootView` opening `NewChatView`. Commit.
- [ ] **Step 9: UI tests.** Update sample-mode UI tests for removed controls; add one asserting the strip's identifier `chatlist.strip` label contains "Connected". Push; require a green `apple.yml` run.

### Task 2: Apple: chat screen actions, history, and search (spec 5.2, 5.3)

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Chats/MessageActions.swift`, `ChatTimeline.swift`, `HistoryPager.swift`, `DraftKeeper.swift`
- Test: `apps/apple/Packages/AppCore/Tests/AppCoreTests/MessageActionsTests.swift`, `ChatTimelineTests.swift`, `HistoryPagerTests.swift`, `DraftKeeperTests.swift`
- Modify: the files this task owns.

**Interfaces (Task 5 mirrors these in Kotlin):**
- `public enum MessageAction: Equatable, Sendable { case reply, react, copy, forward, edit, delete }`
- `public enum MessageActions { public static func available(for message: Message, isMine: Bool) -> [MessageAction] }`, in the spec 5.2 order.
- `public enum ChatRow: Equatable, Identifiable, Sendable { case date(Date), unreadMarker, message(Message) }`
- `public enum ChatTimeline { public static func rows(messages: [Message], firstUnreadID: String?, calendar: Calendar) -> [ChatRow]; public static func dateLabel(for day: Date, now: Date, calendar: Calendar) -> String }`
- `public struct HistoryPager: Sendable { public static let pageSize = 50; public private(set) var reachedStart: Bool; public private(set) var isLoading: Bool; public func shouldLoad(oldestVisibleIndex: Int) -> Bool; public mutating func begin(); public mutating func finish(loadedCount: Int) }`. `shouldLoad` is true when the index is under 5 and neither loading nor at the start; `finish` sets `reachedStart` when `loadedCount < pageSize`.
- `public struct DraftKeeper: Sendable { public mutating func set(_ text: String, for chatID: String); public func draft(for chatID: String) -> String }` (whitespace-only drafts are dropped).
- `AppModel+Chat.swift`: `func olderMessages(in chatID: String, before messageID: String) -> [Message]`, `react`, `edit`, `delete`, `forward(text:to:)`, `searchMessages(_:in chatID:)`, `var drafts: DraftKeeper`.

- [ ] **Step 1: Write failing tests.**
  - `MessageActionsTests`: own text with status `.sent` gives `[.reply, .react, .copy, .forward, .edit, .delete]`; another person's text gives `[.reply, .react, .copy, .forward]`; own photo gives `[.reply, .react, .delete]`; another person's photo gives `[.reply, .react]`; `.deleted`, `.system`, `.call`, and `.unsupported` give `[]` whoever sent them; own text with status `.failed` gives `[.copy, .delete]`.
  - `ChatTimelineTests`: two messages on one day give one `.date` row then two `.message` rows; messages across midnight give two `.date` rows; `firstUnreadID` puts `.unreadMarker` directly before that message and after its date row; an unknown `firstUnreadID` gives no marker; empty input gives `[]`; `dateLabel` returns "Today", "Yesterday", and a medium date for older days.
  - `HistoryPagerTests`: `shouldLoad(4)` true and `shouldLoad(5)` false on a fresh pager; false between `begin()` and `finish`; `finish(loadedCount: 49)` sets `reachedStart` and `shouldLoad` stays false; `finish(loadedCount: 50)` does not.
  - `DraftKeeperTests`: set then get returns the text; a second chat is independent; "   " reads back as "".
- [ ] **Step 2: Run** `swift test`; expect compile failures.
- [ ] **Step 3: Implement the four types.** Run; expect pass. Commit.
- [ ] **Step 4: Chat timeline in `ChatView`.** Render `ChatTimeline.rows`; keep a local array of loaded older messages merged with `chat.messages` by ID (no duplicates, sorted by `sentAt`); drive loading with `HistoryPager` and `olderMessages`; keep scroll position when prepending. Remove the "online" subtitle from `ChatHeaderView`. Empty chat shows "No messages yet". Commit.
- [ ] **Step 5: Unread marker and scroll button.** Capture `unreadCount` and the first unread ID before `markRead`; open scrolled to the marker; show the round "Scroll to newest" button with the new-message count when the newest message is off screen. Commit.
- [ ] **Step 6: Context menu and bars.** `.contextMenu` built from `MessageActions.available`; the six-emoji row from the spec; reply bar and edit bar above the composer in `ComposerView` (new parameters `replyingTo: Message?`, `editing: Message?`, `cancelBar: () -> Void`); delete confirmation with the spec's text; forward via a sheet reusing the contact list; `.textSelection` is not needed because Copy exists. Remove the Location button. If the message under the menu disappears or becomes `.deleted` before the action runs, drop the action and close any bar tied to it. Commit.
- [ ] **Step 7: Bubble details.** "edited" label; delivered = `checkmark.circle`, read = `checkmark.circle.fill` in the accent colour; link detection in text (an `AttributedString` built with `NSDataDetector`); tapping a quote jumps to the original using the pager, highlighting it, and gives up silently when history ends. Commit.
- [ ] **Step 8: In-chat search.** Chat info's Search opens `.searchable` over the chat with results from `searchMessages` filtered to the chat; "No results" when empty; tapping a hit jumps as in Step 7. Drafts via `model.drafts`. Commit.
- [ ] **Step 9: UI tests on sample data:** long-press shows Reply and Copy; choosing Reply shows the reply bar and sending attaches a quote; choosing Edit on an own message shows "Editing"; the scroll button appears after scrolling up. Add `AppCore` tests for the merge-without-duplicates helper if it lives in `AppCore`. Push; require a green run.

### Task 3: Apple: media (spec 5.4). Starts after Task 2 is merged

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Chats/MediaBubbleState.swift`; `apps/apple/App/Features/Media/PhotoThumbnail.swift`, `QuickLookPreview.swift`, `VoicePlayer.swift`, `CameraPicker.swift`
- Test: `apps/apple/Packages/AppCore/Tests/AppCoreTests/MediaBubbleStateTests.swift`

**Interfaces:**
- `public enum MediaBubbleState: Equatable, Sendable { case notDownloaded, downloading(Double), ready(URL), failed }` with `public init(media: MediaState?, fileExists: (URL) -> Bool)`: `.local(url)` where `fileExists(url)` is false maps to `.failed`; `nil` media maps to `.notDownloaded`.
- `@MainActor @Observable final class VoicePlayer { var playingMessageID: String?; var progress: Double; func toggle(messageID: String, url: URL); func stop() }`, one instance per chat screen.

- [ ] **Step 1: Failing tests** for `MediaBubbleState`: each phase maps as above; `.downloading(0.4)` keeps 0.4; a local URL whose file is missing is `.failed`.
- [ ] **Step 2: Implement; run; commit.**
- [ ] **Step 3: Bubbles read real state.** Delete `ChatView`'s `downloaded` set and `showsMedia`; the bubble derives `MediaBubbleState` from `message.media`; Download and retry call `model.chatStore.download`. Photo thumbnails decode with `CGImageSourceCreateThumbnailAtIndex` at the display size, off the main thread; a file that fails to decode shows the neutral photo placeholder. Commit.
- [ ] **Step 4: Open and share.** Tapping a ready photo or file presents Quick Look (`QLPreviewController` on iOS, `QLPreviewPanel` or `.quickLookPreview` on Mac) with the system share action. Commit.
- [ ] **Step 5: Voice playback.** `VoicePlayer` plays the app's Ogg Opus files using code already in the repository and system frameworks; play/pause, progress, elapsed time; one at a time; stop on disappear; audio session category suited to playback and restored after. A file that cannot be decoded shows "Couldn't download". If no route exists without a new dependency, stop and report what is missing. Commit.
- [ ] **Step 6: Camera on iPhone.** The Camera button presents the system camera and sends the photo with `sendAttachment`; hidden on Mac and when no camera is available; add `NSCameraUsageDescription` if absent ("Take photos to send and make video calls."). Refused permission shows the system-standard alert path, not a dead button. Commit.
- [ ] **Step 7:** Outgoing attachment states ("Sending…", "Not sent" + "Try again"). UI test on sample data: a sample photo bubble exposes a "Download" or image element and tapping it does not crash. Push; require a green run.

### Task 4: Android: truth, dead buttons, account, settings (spec 5.1, 5.5, list search and empty states of 5.3)

**Files:**
- Create: `apps/android/core/src/main/kotlin/org/example/app/core/account/ConnectionStatus.kt`; `apps/android/app/src/main/kotlin/org/example/app/ui/search/SearchScreen.kt`, `SearchViewModel.kt`; `ui/chatlist/NewChatScreen.kt`
- Test: `core/src/test/.../ConnectionStatusTest.kt`; `app/src/test/.../SettingsStoreTest.kt`, `ChatIntentTest.kt`, `SearchViewModelTest.kt`

**Interfaces:**
- `enum class ConnectionStatus { Connected, Connecting, Offline }` with `companion fun from(socketConnected: Boolean, hasNetwork: Boolean): ConnectionStatus` (same rules as Task 1). `AppGraph.connectionStatus: StateFlow<ConnectionStatus>`.
- `SettingsStore(prefs: SharedPreferences, initial: UserSettings = UserSettings())` loading on construction and saving on every `update`.
- `fun chatIdFrom(intent: Intent?): String?` in `MainActivity.kt`'s file, reading `EXTRA_CHAT_ID`.
- `AppGraph.signOut()`, `AppGraph.deleteAccount()` (suspend), `ChatStore.unblock(contactId: String)` if a server call exists.

- [ ] **Step 1: Failing tests.** `ConnectionStatusTest` (four cases as Task 1). `SettingsStoreTest`: a value updated in one store instance is read by a second instance over the same preferences; corrupt or missing stored data falls back to defaults. `ChatIntentTest`: an intent with the extra returns the ID; null intent and missing extra return null; a blank extra returns null. `SearchViewModelTest`: a query matches contacts by name and by username case-insensitively, contacts before message hits; a blank query gives empty results.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest`; expect failures.
- [ ] **Step 3: Implement** the four units. Run; expect pass. Commit each.
- [ ] **Step 4: Bind status.** `StatusStrip` and `ServerScreen` show the three states from `graph.connectionStatus` (socket states plus connectivity). Commit.
- [ ] **Step 5: Dead buttons.** New-chat button opens `NewChatScreen` (contacts with an "Add contact" row; choosing calls `ensureChat` and opens the chat). Request chats: `MessageRequestsScreen` rows open the chat, and Task 5 owns the bar inside the chat; here, wire `accept`/`block`/`report` callbacks through `AppRoot` as lambdas Task 5 can call (`onAccept`, `onBlock`, `onReport` on the `CHAT` route are added by Task 5). Notification tap: `MainActivity` handles `chatIdFrom(intent)` in `onCreate` and `onNewIntent` and navigates to the chat. Commit.
- [ ] **Step 6: Search.** A search action in the chat list top bar opens `SearchScreen`; tapping a contact opens the chat; tapping a message hit opens the chat with a `highlight` navigation argument (`chat/{id}?highlight={messageId}`) that Task 5 consumes. Commit.
- [ ] **Step 7: Remove dead controls** per spec 5.1 Android rows: Find by username, Disappearing messages (Privacy and chat info), chat info Mute; delivery radio becomes a read-only row naming the transport from `PushTransport`. Find by email calls the server; reverts with an error on failure. Commit.
- [ ] **Step 8: Account.** Profile page with "Sign out" and "Delete account" and the spec's confirmation texts; both wipe local data and return to onboarding only after the server call succeeds; on failure show "No connection". Blocked list with names and "Unblock" (stop and report if the server has no unblock). Empty states for chat list and contacts. Commit.
- [ ] **Step 9: Emulator test:** launch in sample mode, tap new chat, pick a contact, the chat opens; open search, type a sample contact's name, the contact appears. Run locally; all green; `./gradlew --stop`.

### Task 5: Android: chat screen actions, history, and search target (spec 5.2, 5.3)

**Files:**
- Create in `apps/android/core/src/main/kotlin/org/example/app/core/chats/`: `MessageActions.kt`, `ChatTimeline.kt`, `HistoryPager.kt`, `DraftKeeper.kt`
- Test: matching files under `core/src/test/...`
- Modify: files this task owns.

**Interfaces:** the Kotlin mirror of Task 2: `enum class MessageAction { Reply, React, Copy, Forward, Edit, Delete }`; `object MessageActions { fun available(message: Message, isMine: Boolean): List<MessageAction> }`; `sealed interface ChatRow { data class DateRow(val day: LocalDate); data object UnreadMarker; data class MessageRow(val message: Message) }`; `object ChatTimeline { fun rows(messages: List<Message>, firstUnreadId: String?, zone: ZoneId): List<ChatRow> }`; `class HistoryPager { companion object { const val PAGE_SIZE = 50 } ... }` with the same three operations; `class DraftKeeper`. Adds `DeliveryStatus.Failed` to `core` and `fun retrySend(messageId: String)` to `ChatStore` (default no-op; `LiveChatStore` delegates to its existing retry). `ChatScreen` gains `onAccept`, `onBlock`, `onReport`, `onForward`, and `highlightMessageId: String?`.

- [ ] **Step 1: Failing tests,** the same cases as Task 2 Step 1, plus: an outbox-failed message maps to `DeliveryStatus.Failed` in the `messaging` mapping test, and `available` for a failed own text is `[Copy, Delete]`.
- [ ] **Step 2: Run; expect failures. Step 3: Implement; run; pass; commit.**
- [ ] **Step 4: Timeline and paging** in `ChatViewModel`/`ChatScreen`: rows from `ChatTimeline`; older pages through `store.messages(chatId, before, PAGE_SIZE)` merged by ID without duplicates; position kept when prepending (reverse layout); "No messages yet". Commit.
- [ ] **Step 5: Unread marker and scroll button** as Task 2 Step 5. Commit.
- [ ] **Step 6: Long-press menu and bars.** `combinedClickable` long-press opens a menu from `MessageActions.available` with the six-emoji row; reply and edit bars in `Composer`; delete confirmation; forward through a contact picker; failed bubbles show "Not sent" and "Try again". Same vanished-message rule as Task 2 Step 6. Commit.
- [ ] **Step 7: Bubble details and jumps.** Clickable links (`LinkAnnotation` / `Linkify`), quote tap jumps to the original with paging, `highlightMessageId` from search scrolls and highlights. Request chats show the Accept / Block / Report bar instead of the composer. In-chat search from chat info. Drafts kept per chat. Commit.
- [ ] **Step 8: Emulator tests on sample data:** long-press shows Reply and Copy; Reply shows the bar and the sent message carries a quote; Edit shows "Editing"; scrolling up shows the scroll button. Run locally; green; `./gradlew --stop`.

### Task 6: Android: media (spec 5.4). Starts after Task 5 is merged

**Files:**
- Create: `core/.../chats/MediaBubbleState.kt` and test; `app/.../ui/media/PhotoDecoder.kt`, `PhotoViewerScreen.kt`, `VoicePlayer.kt`, `AttachmentPickers.kt`; a `FileProvider` entry and `res/xml/file_paths.xml` if none exists.
- Test: `MediaBubbleStateTest.kt`, `PhotoDecoderTest.kt` (sample-size calculation).

**Interfaces:** `sealed interface MediaBubbleState { NotDownloaded, Downloading(fraction), Ready(path), Failed }` built from the message's media state and a `fileExists` function, same rules as Task 3. `object PhotoDecoder { fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int }` returning a power of two, at least 1, such that the decoded image is no smaller than the target.

- [ ] **Step 1: Failing tests:** state mapping as Task 3; `sampleSize(4000, 3000, 1000, 750) == 4`; `sampleSize(800, 600, 1000, 750) == 1`; zero or negative sizes give 1.
- [ ] **Step 2: Implement; run; commit.**
- [ ] **Step 3: Show photos.** Bubbles decode with `BitmapFactory` and `inSampleSize` off the main thread, cached in an `LruCache` sized to an eighth of the heap; undecodable files show the neutral placeholder. Tap opens `PhotoViewerScreen` (pinch zoom, share). Commit.
- [ ] **Step 4: Files** open with `ACTION_VIEW` through the `FileProvider`, with a "no app can open this file" message when nothing handles it, and a share action. Commit.
- [ ] **Step 5: Voice playback** with `MediaPlayer`: play/pause, progress, one at a time, released on leaving the chat; errors show "Couldn't download". Commit.
- [ ] **Step 6: Sending.** Photo (`PickVisualMedia`), File (`OpenDocument`, copied into app storage before `sendAttachment`), Camera (`TakePicture` into a `FileProvider` URI); microphone button records with the existing `VoiceRecorder` behind a recording bar (elapsed time, Cancel, Send); permissions requested on first use; refusal shows a one-line explanation and leaves the app usable. Commit.
- [ ] **Step 7: Emulator test:** in sample mode the attach sheet's Photo launches the picker intent (assert with an intent stub), and a sample photo bubble is displayed. Run locally; green; `./gradlew --stop`.

### Task 7: Apple: call media and video (spec 6)

**Files:**
- Modify: `apps/apple/project.yml`, `apps/apple/App/Calls/MediaSeam.swift`, `apps/apple/App/Features/Calls/CallView.swift`, `CallControlsView.swift`, the app `Info.plist` settings in `project.yml`
- Create: `apps/apple/App/Features/Calls/VideoStageView.swift`
- Test: existing `CallCore` and `CallMedia` tests; `AppUITests` call tests on the simulated engine.

**Interfaces:** `LiveMedia.make()` returns `CallMedia`'s public `MediaSession` implementation. `VideoStageView(remote:local:avatarName:)` wraps the WebRTC Metal video view in `UIViewRepresentable` / `NSViewRepresentable`. Consumes whatever video-track and camera-switch API `CallMedia` already exposes; if `CallMedia` exposes no way to attach a renderer or switch camera, add the smallest public method to `CallMedia` that does, with a test.

- [ ] **Step 1:** Read `Packages/CallMedia` and `Packages/WebRTCBinary/Package.swift`; confirm the release URL and checksum resolve (`tools/webrtc/check-package-pin.sh`).
- [ ] **Step 2:** Add `CallMedia` to `packages:` in `project.yml` and to the iOS and macOS app targets' dependencies (not the notification extension). Replace `UnavailableMediaSession` with the real session; delete the stand-in. Push; the app must build and existing tests pass on CI before continuing.
- [ ] **Step 3:** `VideoStageView` replaces the placeholder: remote full screen, local corner tile, avatar until the first remote frame and whenever remote video is off. Commit.
- [ ] **Step 4:** Flip camera works on iPhone; hidden on Mac or with one camera. Camera permission requested at first video use; on refusal the call continues as audio with "Camera access is off. Turn it on in Settings." Commit.
- [ ] **Step 5:** Set `MediaCapabilities.enhancedNoiseSuppression` from what the linked binary supports; report which. Add a `CallMedia` test that configuring the real session with empty ICE servers succeeds and `close()` finishes the event stream. Push; require green `apple.yml` and `mac-build.yml` runs. Report what could not be verified without real devices.

### Task 8: Android: call video and network changes (spec 6)

**Files:**
- Create: `apps/android/app/src/main/kotlin/org/example/app/ui/call/VideoStage.kt`; `calls-android/.../NetworkWatcher.kt` and test
- Modify: `ui/call/CallScreen.kt`, the WebRTC media session in `calls-android` (renderer attach, camera switch), call wiring in `AppGraph`.

**Interfaces:** `class NetworkWatcher(connectivity: ConnectivityManager, onChange: (NetworkKind) -> Unit) { fun start(); fun stop() }` using the engine's existing network type; duplicate consecutive values are not re-reported. `VideoStage(remote, local, avatarName)` hosting `SurfaceViewRenderer` in `AndroidView`, initialised with the session's `EglBase` and released on dispose.

- [ ] **Step 1: Failing test** for `NetworkWatcher` with a fake callback source: Wi-Fi then mobile reports two changes; Wi-Fi twice reports one; `stop()` unregisters.
- [ ] **Step 2: Implement; run; commit.** Start the watcher when a call starts and stop it when the call ends; feed the engine's existing network setter.
- [ ] **Step 3:** `VideoStage` replaces the placeholder with the same avatar rules as Task 7. Renderers are released when the call ends or the screen leaves composition; no leak across two consecutive calls. Commit.
- [ ] **Step 4:** Flip camera (hidden with one camera). Camera permission at first video use; refusal continues as audio with the spec's message. Commit.
- [ ] **Step 5:** Emulator test on the simulated engine: starting a video call shows the stage and the flip control state matches the camera count. Run unit and emulator tests locally; green; `./gradlew --stop`. Report what could not be verified without two real devices.

---

## Review

- UI changes (Tasks 1 to 6, and the screens of 7 and 8): reviewed by the lead against the spec.
- Everything else (logic, account actions, settings persistence, call media linking, permissions, network handling): reviewed by Opus reviewers before merge.
- Each task's report lists: what was built, test results with commands, the CI run link for Apple tasks, anything stopped-and-reported, and what was not verified on a live server or real device.
