# Apple app (iPhone + Mac) inventory, from source, 2026-11-18
Paths relative to repo root, under apps/apple/. Statuses: WORKING / PARTIAL / SAMPLE-ONLY / STUB / ABSENT.
Method: read App/, NotificationService/, AppCore, Messaging (LiveChatStore, Outbox), project.yml. Nothing built or run. "Live" = signed-in real server; "sample" = `-sampleData` launch arg (UI tests only).

KEY STRUCTURAL FACT: the back-end packages (Messaging, LocalStore, ServerKit, SecureMessaging, CallCore) are far ahead of the UI. `ChatStore` (Packages/AppCore/Sources/AppCore/Chats/ChatStore.swift) and `LiveChatStore` (Packages/Messaging/Sources/Messaging/Store/LiveChatStore.swift) implement react, edit, delete, reply (`send(replyTo:)`), download, `messages(before:)` paging, message search. NO screen calls any of them (grep of App/ finds none). `CallMedia` + `WebRTCBinary` packages are NOT in project.yml packages list, so the app has no WebRTC.

## 1. Onboarding
- Welcome (value points, Get started / I already have an account): WORKING. App/Features/Onboarding/WelcomeView.swift
- Server selection: WORKING. Default server card, "Scan invite QR", "Paste invite link", "Join another server" (host entry). JoinServerView.swift; pendingInvite from deep link (AppModel.handle(url:)). `connect(host:)` in LiveAccountService.
- Invite code (beta gate), sign-up (name, username, email): WORKING (InviteCodeView / CreateAccountView in SignUpSteps.swift -> LiveAccountService).
- Email verification (6-digit code): WORKING (VerifyEmailView). Resend control not seen (unverified; I did not find a Resend button in SignUpSteps.swift).
- Passkey creation or "email code instead": WORKING (PasskeyView; ServerKit/Passkeys). Passkeys optional per server.
- Permissions step (notifications, mic): WORKING. Camera permission not requested (no camera use).
- Login on existing account: PARTIAL. "I already have an account" goes to device-link (QR) or LostDeviceView (passkey or email code). No plain username/password; by design.
- Device linking: WORKING both directions. New device shows QR (LinkDeviceView, iPhone and Mac); signed-in iPhone scans + Face ID approve (Settings/LinkedDevicesView.swift, LinkApprovalView.swift); contact/chat/folder snapshot transferred. Scanning side is iPhone only (Mac has no scanner; `#if os(iOS)`). Mac can also create an account (latest commit).
- Account recovery: PARTIAL. LostDeviceView signs in via passkey or email code; history starts empty (by design, "only from a backup"; no backup feature exists). Help text mentions backup but none exists.
- Account deletion: WORKING. Settings > profile (Mac: Settings > Account) > Delete account, confirm, calls server, wipes local. Settings/SettingsView.swift ProfileView.
- Sign-out: ABSENT as a user button (only delete account or server-ended session). No "Log out" found.
- Edit profile (name, avatar): ABSENT. ProfileView is read-only (LabeledContent).

## 2. Chat list (App/Features/ChatList/*)
- Row: avatar (initial on gradient), name, time, last-message preview with SF symbol, unread count badge: WORKING (ChatRowView.swift). No sender prefix, no delivery tick in row.
- Unread badges: WORKING per chat; no total app-icon badge (see 10).
- Pinning: ABSENT (no field in `Chat` model). Archiving: ABSENT. Per-chat muting: ABSENT in list.
- Swipe actions / context menu on rows: ABSENT (no swipeActions/contextMenu anywhere in App/). Cannot delete, clear, pin or mute a chat from the list.
- Search: WORKING. iPhone iOS 26 has a Search tab (chats, messages via store FTS, contacts); iOS 17-25 and Mac use a `.searchable` filter on name and message text. Results show chat rows, not the matching message or jump to it.
- Folders: WORKING. All / Unread / custom chips; create, rename, reorder, delete in Settings > Chat folders (ChatFoldersView.swift); synced to linked devices. Assigning a chat to a folder happens only in NewFolderView at folder creation (no per-chat "add to folder" action seen).
- Empty states: PARTIAL. Message requests and No calls have them; empty chat list shows no empty-state view (List is just blank; I saw none in ChatListContent).
- Connection status: STUB. StatusStripView.swift draws a hard-coded green dot and "Connected to <host>" regardless of real socket state; the real `ConnectionState` is private in AppModel and never shown. Settings > Server also hard-codes "Connected".
- Typing / online indicators: ABSENT. Chat header status is the literal string "online" for everyone (ChatView.swift line 33), always. FAKE.
- New chat: PARTIAL. iPhone compose button opens contact picker (NewChatView.swift). Mac has no new-chat button (start from Contacts sidebar).
- Message requests row: WORKING (MessageRequestsView.swift).
- "Mark all as read" menu: WORKING. "More" menu iPhone only.
- Mac: sidebar (folders, Calls, Contacts) + list + detail (MacRootView.swift).

## 3. Chat screen (ChatView.swift, MessageBubbleView.swift)
- Bubble layout: WORKING, own right accent gradient, theirs left, tail corner, time + tick inside. No sender grouping/clustering (each bubble its own row, 6pt gap), no avatars in chat.
- Date separators: ABSENT. Unread marker: ABSENT. Scroll-to-bottom button: ABSENT (opens anchored at bottom via defaultScrollAnchor only; no scroll on new message observed).
- Delivery/read marks: WORKING (sending/sent = single check, delivered/read = filled check circle; delivered vs read not visually different from each other: both `checkmark.circle.fill`). Failed: "Not sent" + "Try again" button wired to retrySend. Read ticks respect Read receipts switch.
- Reply/quote: PARTIAL. Quote block RENDERS for received/stored replies (replyToID), but there is NO way to start a reply (no swipe, no menu). `send(..., replyTo:)` unused by UI.
- Forward: ABSENT. Edit: ABSENT in UI (store `edit()` works; no entry point; "edited" label not shown). Delete: ABSENT in UI (store `delete()` works; deleted messages DO render as "This message was deleted"). Reactions: PARTIAL, display only (small capsule of emoji at bubble corner); cannot add one. Copy: ABSENT (no context menu; text not even `.textSelection`). Select multiple: ABSENT.
- Message search in chat: ABSENT (Chat info "Search" runs the global chat-list filter with the contact name).
- Drafts: PARTIAL. `@State draft` per ChatView; lost on leaving chat/app (not persisted).
- Link previews, text formatting (markdown), mentions: ABSENT. URLs are plain `Text` (SwiftUI Text(String) does not auto-link).
- Pagination: ABSENT in UI. ChatView renders `chat.messages`, which is only the latest 50 per chat (`cachedMessageCount = 50`, LiveChatStore.swift line 22); no way to load older. Store `messages(before:)` exists, unused. Older history in local DB (and search) is unreachable by scrolling.
- Message requests chat: Accept / Block / Report bar replaces composer: WORKING.
- Voice bubble: STUB. Play button + fake static waveform; no tap action, no playback code (no AVPlayer anywhere in App/). Voice messages can be recorded and sent but never played back, on either side.
- Photo/file bubble: STUB (see 5).
- Call log bubbles: WORKING. System lines (device added, safety number changed, paused): WORKING. Undecryptable flag exists in model, not shown by UI.
- Audio/video call buttons in header: WORKING for start (but see 9: live media is unavailable).

## 4. Composer and attachments (ComposerView.swift, ComposerMedia.swift, AppModel+Attachments.swift)
- Text send (return or arrow): WORKING, enqueues to outbox. Max length truncation in store.
- Photo (PhotosPicker, images only): PARTIAL. Picks, store re-encodes (MediaPreparation), encrypts, uploads, queues. No preview/caption/crop before send, no multi-select.
- Camera: SAMPLE-ONLY / STUB. Button sends `sendSample(.photo(bytes: 310_000))` even live. Live store ignores non-text `send`, so tapping Camera does nothing on a live account.
- Location: STUB. Sends the literal text "📍 Shared location" via sendSample; on a live account this really is sent to the contact as plain text (a fake location). No real location.
- Video: ABSENT (photos picker `.images` only; Files can carry a video as an opaque file).
- File (fileImporter, any type): PARTIAL, real upload; no size limit shown in UI (no limit constants found in UI path; I did not find an enforced max, so uncertain). 
- Voice message: WORKING to record and send (tap mic, tap stop; no hold-to-record, no waveform/timer shown while recording, no lock/slide cancel; trash cancels). Min length and 30 min max enforced (MediaPlan). Ogg Opus conversion in Messaging/Attachments/OggOpus.
- Send preview: ABSENT. Upload/download progress UI: ABSENT (store reports progress via MediaState.phase; UI never reads `message.media`). Retry failed sends: WORKING for messages (Try again), via outbox retries (10 attempts with backoff then "failed").
- Emoji/stickers/GIFs: ABSENT. Attachment of contacts: ABSENT.

## 5. Media viewing
- Image viewer: ABSENT. Video player: ABSENT. Save/share: ABSENT. Gallery per chat: ABSENT (Chat info shows only a count "Media, files and links: N", not tappable).
- Photo/file bubbles: STUB. MessageBubbleView.MediaView draws a blurred gray box with "Photo · 240 KB" and a download arrow; tapping only inserts the id into a local `downloaded` Set in ChatView (does NOT call `chatStore.download`); then it shows a blue/purple gradient with a symbol. The actual decrypted image/file is never displayed, opened or exportable, on either iOS or Mac. (Store may auto-download in background per settings, but nothing renders the result.)
- Auto-download settings (Wi-Fi / mobile / voice always): WORKING in settings and passed to store (`messaging.autoDownload`), but the bubble ignores real state.

## 6. Contacts and finding people
- Contacts list (alphabetical sections, search): WORKING (ContactsView.swift). Tap opens chat. No contact detail page apart from chat info; no remove-contact.
- Add by username: WORKING (lookUpUser). Not-found message shown.
- QR code to add a contact: STUB. "Scan QR code" in AddContactView hard-codes username "neha" and adds her (sample code left in live build, AddContactView.swift). No "my QR code" screen either (QR exists only for device linking and server invite scanning).
- Invite link: STUB/WRONG. "Invite people" ShareLink builds https://<server>/invite/BETA-2026 from `SampleAccountService.sampleInviteCode` even on live accounts (ContactsView.swift line ~41). Not a real per-user invite.
- Message requests: WORKING (accept, block, report). Calls from strangers do not ring (become missed call in request).
- Blocking: WORKING to block (chat info, request bar; pending action sent to server, chat deleted locally). Unblocking: ABSENT. Blocked list shows raw account IDs, not names (PrivacyView BlockedListView).
- Reporting: WORKING (last 10 messages as text lines sent to server admin via pending action).
- Verification: WORKING when engine gives real keys: safety number sheet (12 groups), verified toggle, "Verified" seal. Gated by `supportsSafetyNumbers` = `deps.mls.providesRealMemberKeys` (LiveChatStore.swift ~411, carries a TODO(M7)); not confirmed whether the shipped engine returns true. No QR/compare-by-scan.
- Find me by username / by email toggles: STUB. Local-only booleans in UserSettings (findByUsername never sent anywhere; `updateSettings(findByEmail:)` exists in ServerClient but only called for displayName).

## 7. Groups
- ALL ABSENT. `Chat` has a single `contactID` (1:1 only); no group model, UI, create, members, roles, avatar, description or leave. ("groups" in the code are per-pair MLS groups.)

## 8. Chat info / profile pages
- Chat info (ChatInfoView.swift): WORKING for avatar, name, @username, Audio / Video call buttons, encryption line (only place it is shown, per rule), mode-mismatch warning, Verify, Report, Block.
- Mute button: STUB. Local `@State muted` toggle that only flips the icon; not persisted, no effect on notifications.
- Search button: PARTIAL. Runs the chat-list search for the contact's display name (not in-chat search).
- Media, files and links row: STUB count only, not tappable. Disappearing messages row: STUB (static "Off"; Settings > Privacy picker is a local value with no effect on messages; SettingScope mentions it but no expiry code in UI/store path I checked).
- Own profile (Settings header): PARTIAL, read-only name/username; no avatar change, no edit. Contact avatars: generated initial on gradient only; no photos anywhere.
- Contact detail (nickname, shared groups, etc.): ABSENT.

## 9. Calls
- Signalling + state machine (CallCore, LiveCallSignaling over WebSocket, sealed via MLS), call UI, CallKit, VoIP push, Mac ringer: built and tested.
- ACTUAL MEDIA: STUB. App/Calls/MediaSeam.swift `LiveMedia.make()` returns `UnavailableMediaSession`, whose configure throws, so a live call ends as failed instead of connecting. CallMedia/WebRTCBinary packages exist but are not linked in project.yml. Audio and video calls therefore do not carry audio or video today. (Confidence: high; read the seam file and project.yml packages. I did not run it.) Sample mode simulates a call with fake timer/quality buttons (UI tests only).
- Incoming call UI: iPhone CallKit via VoIPPushCenter (PushKit), full-screen CallView; Mac in-app banner + MacRinger (sound, Dock badge, notification actions). WORKING as UI, nothing to talk through. Mac `Simulate incoming call` toolbar button is DEBUG-only.
- In-call controls: speaker, mute, video toggle, data mode sheet, info sheet: wired to engine (CallControlsView.swift). "Flip camera" button is an empty closure `{}` (STUB). Video stage is a placeholder person glyph and gray "You" tile ("Placeholder video until the WebRTC engine exists", CallView.swift).
- Call history tab: WORKING from call messages in chats (All/Missed filter; tap to call back). No per-call detail, no delete.
- Quality selection: WORKING as setting (Data mode Auto / levels, mobile data limit, video off on mobile; server max/default) feeding policy code; effect depends on media which is absent.
- Noise suppression: PARTIAL. Setting UI (Standard/Enhanced/Off); Enhanced greyed "Not available on this device yet" (`MediaCapabilities.enhancedNoiseSuppression = false`); Voice Isolation hint opens system mic menu. RNNoise code is in CallMedia, unlinked.
- Network change: WORKING in logic (PathMonitor -> engine.pathChanged, ReconnectPolicy, ICE restart in CallCore, tested with fakes); unverifiable end to end without media.
- Call banner in app when call active: present (CallBannerView).

## 10. Notifications
- Push (APNs via server gateway, NSE decrypts and shows sender name + text, thread identifier): PARTIAL/WORKING. Requires pushGatewayKey configured and matching server gateway (AppModel.didReceivePushToken), else push is silently off.
- Generic fallback ("New message") on failure/timeouts: WORKING. NSE memory/time budget handled.
- Grouping: PARTIAL (threadIdentifier set per chat). Reply from notification: ABSENT (no UNNotificationCategory/text input action for messages). Mark-as-read action: ABSENT.
- Call notification (NSE call branch, Answer/Decline on Mac): present.
- Mute options: ABSENT. Settings > Notifications has 3 toggles (Message notifications, Ring for calls, Show message text) stored in @AppStorage but NEVER READ by app or NSE (grep for notify.* finds only the view): STUB, they do nothing.
- Badge count: ABSENT on iPhone (no badge set by NSE or app; permission requested only). Mac: Dock badge for ringing only.
- Local "New device added" notification: WORKING (LocalDeviceNotifier).

## 11. Settings (iPhone SettingsView.swift; Mac window = same views as tabs)
- Profile header: read-only. Delete account: WORKING.
- Server: PARTIAL. Shows host and fake "Connected" only; text says switching server doesn't move data; no way to change server after sign-in.
- Linked devices: WORKING (list, swipe to remove, link new via QR scan [iPhone only]). Removal revokes server-side and re-keys groups.
- Privacy: IP hiding (3 options): WORKING (feeds relay policy in calls; again no media). Who can find me (username/email): STUB (local only). Read receipts: WORKING (send and show). Disappearing messages: STUB (no effect found). Blocked list: PARTIAL (IDs, no unblock).
- Data & Storage: call data mode / mobile limit / video-off: WORKING as settings; auto-download scope: WORKING as settings. "This month" usage bar (42/28/12 MB) and "Storage on this device 312 MB": SAMPLE-ONLY hard-coded numbers shown to every user (DataStorageView.swift comment "Sample numbers until real counters exist"). No clear cache/storage management.
- Notifications: STUB (above). Chat folders: WORKING. Calls & Audio: PARTIAL (above). Appearance (System/Light/Dark): WORKING (@AppStorage applied in RootView; no accent colour/wallpaper/text size/bubble options).
- Help: WORKING static text (3 tips). Open-source licenses: WORKING (LicensesView). 
- Absent settings: language, app lock/Face ID, backup/export, storage usage, about/version, support/feedback, sessions/devices beyond list, passkey management, email change, username change, sign out.
- Settings persistence: UserSettings in memory + synced via DeviceSync scope for some; call/data settings were `didSet`-wired; I did not verify persistence across relaunch for `settings` (appears in-memory, initial from server policy each launch).

## 12. Offline and reliability
- Local database: WORKING. SQLite (LocalStore: Database, Repository, migrations, FTS search, file protection, process lock shared with NSE).
- Offline send queue: WORKING. Outbox with per-message retry, backoff, 10 attempts then "failed" with manual Try again, idempotent clientMsgID, queued controls (react/edit/delete/receipt) behind messages.
- Reconnect/backoff: WORKING. LiveConnection with full-jitter exponential backoff 1-60 s; PathMonitor; on connect: inbox sync, device reconcile. iPhone socket only while app active (background relies on push); Mac keeps socket.
- Sync after reconnect: WORKING (syncInbox on connect/foreground/push). Multi-device sync of contacts/folders/blocks/read state: WORKING (DeviceSync).
- Pagination: ABSENT in UI (50-message window, see 3).
- User-visible offline state: ABSENT (no banner, no "connecting..."; status strip is fake green).
- Failure handling: blocking alert "Your chats couldn't be opened" with Try again if the live store cannot be built. Sign-out wipe is crash-safe.
- Attachment retry/resume: store supports resumable download (offset); no UI.

## 13. Look and polish
- Theming: system/light/dark only; iOS 26 glass styles (GlassStyle, Glass.swift) with fallbacks to 17. No custom themes/wallpapers/accent.
- Dark mode: WORKING (system colours, preferredColorScheme override).
- Avatars: generated initial gradient only; no photos.
- Animations: minimal (default withAnimation in onboarding, Mac banner transition); no message send/receive animation, no reaction animation, no swipe gestures.
- Accessibility: GOOD baseline. VoiceOver labels on controls, accessibility identifiers, Dynamic Type adaptive row layout (@ScaledMetric, AnyLayout), AccessibilityTests UI test. Chat bubbles lack combined labels with sender/time (not checked in depth).
- Localisation: PARTIAL. All strings in String Catalog (App/Resources/Localizable.xcstrings, 105 entries) but ONLY English exists; no translations. A few strings are hard-coded in AppCore (Message.previewText, e.g. "Update [App] to see this message").
- iPad: ABSENT. TARGETED_DEVICE_FAMILY '1' (iPhone only); portrait-only (UISupportedInterfaceOrientations = Portrait). No landscape.
- Mac layout: WORKING 3-column NavigationSplitView (sidebar, list, chat), Settings scene as window tabs, separate Call window, macOS 14+. Mac lacks: New chat button, link-scanner, camera-based features, notification reply, menu commands/keyboard shortcuts (none found), drag-drop of files.
- Brand: placeholder `[App]` (AppBrand.swift); app icon not checked.

## 14. Other notable / rough edges
- Fake/sample code reachable in live builds: "online" header, green status strip, "Connected" in Server settings, Camera button, Location button, QR "Scan" adding "neha", sample invite link, Data & Storage numbers.
- Sample mode flag `-sampleData`: `SampleChatStore`, `SimulatedCallEngine`, `SampleAccountService` are test scaffolding that the UI tests run against; a live account never sees sample chats (guarded by AppStores). So the UI-test suite does not exercise the real messaging path in the UI (only package tests do).
- E2E tests for apps (AppUITests/iOS, macOS) are sample-driven; end-to-end live UI flow not covered.
- Security design is strong: MLS engine (LiveMLSEngine, FFI to Rust-ish mlsffi), NSE cross-process lock, escrow for server-readable chats, wipe-on-revoke. Interop peer tool (Interop/Sources/interop-peer).
- No analytics/crash reporting (not seen). No in-app update/version info.

## Status counts (features listed above, approximate)
See final report.
