# Our Android app: inventory from source (apps/android), 2026-11-18, main @ f9685c3

Method: read app/ screens, AppRoot/AppGraph wiring, core ChatStore interface, messaging LiveChatStore/Outbox, net, push, calls-android. No build or run. Paths relative to repo root. Prefix A = apps/android/app/src/main/kotlin/org/example/app.
Key finding: the plumbing (MLS, outbox, inbox sync, push, WebRTC, Room/SQLCipher) is deep and real; the screens on top are thin. Many store capabilities (react, edit, delete, retry, search, accept, voice, attachments) have NO UI caller.
Legend: W=WORKING, P=PARTIAL, S=SAMPLE-ONLY, STUB, ABSENT. Live build = sampleData=false (default). Sample mode only via test launch extra (A/MainActivity.kt, A/App.kt).

## 1. Onboarding
- Server selection: W. Default server, invite link/text, or typed address, QR scan of invite. A/ui/onboarding/JoinServerScreen.kt, OnboardingViewModel.kt; net/.../account/LiveAccountService.kt (connect)
- Invite code redeem: W. A/ui/onboarding/InviteCodeScreen.kt -> redeemInvite
- Sign-up: W. Name, username (live availability check), email -> email code -> passkey (Credential Manager) or email-code-only fallback. A/ui/onboarding/CreateAccountScreen.kt, VerifyEmailScreen.kt, PasskeyScreen.kt
- Login on a fresh phone: P. No username/password. Only "I already have an account" -> link from another device via QR (W) or Lost-device page: passkey or email code (W). Old messages NOT restored. LinkDeviceScreen.kt, LostDeviceScreen.kt
- Device linking (new device shows QR, old phone scans + biometric approve): W. LinkDeviceScreen.kt, LinkApprovalScreen.kt, LinkedDevicesScreen.kt, net/.../LinkFlow.kt; snapshot export/import in messaging/.../DeviceSync.kt
- Verification: W for email code. No phone-number verification (not designed).
- Account recovery: W. sendRecoveryCode/recover via email; passkey sign-in. Same as lost-device.
- Account deletion: ABSENT in UI. AccountService.deleteAccount() exists (net LiveAccountService.kt:349) but nothing in A/ calls it (grepped).
- Sign out / log out: ABSENT in UI (only server-driven sign-out and revocation wipe, AppGraph.kt).
- Permissions step (notifications + microphone only; camera not asked): W. PermissionsScreen.kt
- Revoked device: W. 4003 close -> full local wipe -> back to onboarding (AppGraph.wipeAfterRevocation, LocalData.kt)

## 2. Chat list (A/ui/chatlist/*)
- Row: avatar = initial letter in circle (no photos), name, one-line preview, time, unread badge: W. ChatRow.kt
- Unread badge count: W (store unreadCount; markRead on open). No per-chat mute badge style.
- Pinning: ABSENT. Archiving: ABSENT. Per-chat mute: ABSENT (ChatRecord has `muted` column but no UI/logic; chat info Mute button is local-state only, see 8).
- Search (chats or messages): ABSENT in UI. ChatStore.searchMessages() + FTS5 index exist (LiveChatStore.kt:290, store/AppDatabase.kt) but no screen calls it.
- Folders: W. Chips All / Unread (count) / custom; create/rename/delete/reorder in Settings > Chat folders (ChatFoldersScreen.kt, ChatFolderForms.kt, store persistence). Adding a chat to a folder happens only in the folder-create form (chat picker); no per-chat "move to folder" action.
- Swipe actions: ABSENT. Long-press menu on a row: ABSENT.
- Overflow menu: only "Mark all read" (W).
- New-chat FAB: STUB. `onNewChat = {}` in A/AppRoot.kt (button does nothing).
- Message requests row ("N requests") -> list: W for viewing (MessageRequestsScreen.kt).
- Empty state of chat list: ABSENT (blank list). Contacts tab empty state also absent. Requests and Calls tabs have empty text.
- Connection status: STUB-ish. StatusStrip shows server host + data-mode label with an always-green dot; not bound to socket state (socket.states is not read by any UI). No "connecting / offline" indicator anywhere. StatusStrip.kt
- Typing indicator: ABSENT. Online/last-seen: ABSENT. (no protocol for them found)
- Draft preview in row: ABSENT.

## 3. Chat screen (A/ui/chat/ChatScreen.kt, MessageBubble.kt, ChatViewModel.kt)
- Bubble layout: W (own right, other left, rounded, max 300dp, time, ticks). Reverse-layout list, auto-scroll to newest when at bottom or own send.
- Grouping of consecutive messages: ABSENT (each is its own bubble; no sender name since 1:1 only).
- Date separators: ABSENT.
- Delivery/read marks: W. Sending/Sent = single check, Delivered/Read = double check, Read in primary colour; read receipts honour setting (Receipts.kt). Failed state: NOT shown (core has no failed status; failed sends display as "sending").
- Reply/quote: P. Quote of replied message renders if the message has replyToId (W for incoming); no way to START a reply (no long-press/swipe; ChatViewModel.send has replyTo param unused by UI).
- Forward: ABSENT. Edit: P (store.edit works + "edited" label shown; no UI to edit). Delete: P (store.delete works, "This message was deleted" renders; no UI). Reactions: P (store.react works; reactions render as joined emoji text; no UI to react). Copy: ABSENT. Select multiple: ABSENT. No long-press handler anywhere on a bubble.
- Failed-send retry: ABSENT in UI (LiveChatStore.retrySend and failedMessageIds exist, never called from A/).
- Scroll-to-bottom button: ABSENT. Unread marker: ABSENT.
- In-chat message search: ABSENT (chat info "Search" is a no-op `onSearch = {}`, AppRoot.kt).
- Drafts: P. Composer text kept via rememberSaveable (survives rotation/process death for the screen), not persisted per chat across navigation.
- Link previews: ABSENT; links not even clickable. Text formatting/markdown: ABSENT (plain Text). Mentions: ABSENT.
- Pagination / older messages: ABSENT. UI shows only the cached last 50 messages per chat (LiveChatStore.CACHED_MESSAGES=50, ChatViewModel reads chat.messages); store.messages(before,limit) exists but unused. Older history unreachable.
- Photo bubbles: P/STUB. Shows grey tile + download button + size label; after download (Phase.Local) NO image is drawn (no image loader in app; PhotoPrep is outgoing-only). File bubbles: name+size tile; no open/share on tap.
- Voice bubbles: STUB. Play icon with no click handler and duration text; no player (no MediaPlayer/ExoPlayer in A/).
- System lines (device added, safety number changed, call records): W.
- "Cannot open this message" for undecryptable: W.
- Sending paused banner: W (placeholder text "sending paused" when mode mismatch).

## 4. Composer and attachments (A/ui/chat/Composer.kt)
- Text send: W (limit via TextLimit, trimmed, queued in Outbox with retry/backoff, ID-based idempotent).
- Attach button + sheet (Photo / Camera / File): STUB. Sheet opens, but ChatScreen's `onAttach` defaults to `{}` and AppRoot does not pass one: tapping does nothing. No photo picker, no camera intent, no file picker anywhere (grepped ActivityResultContracts: only permissions).
- Mic button (voice message): STUB. `onRecordVoice = {}` not wired. VoiceRecorder, Opus/Ogg encoder, VoiceNoteFinisher exist in A/media/ and are tested but have zero callers from UI (grepped).
- Send path behind the UI: W in store. LiveChatStore.sendAttachment: PhotoPrep (scale+thumbnail), AttachmentCipher, upload, outbox; Downloader with progress and size-limit refusal (maxAttachmentBytes hardcoded 100 MB; TODO(I1) read from /v1/server). Reachable only from tests.
- Video attachments: ABSENT (Preparer returns null for MediaKind.Video).
- Camera capture: ABSENT. Send preview/caption: ABSENT. Size limit message: ABSENT in UI.
- Download progress: W (ring on tile). Auto-download rules (wifi/mobile/voice): W via AutoDownloadPolicy + Settings.
- Upload progress/ retry: ABSENT in UI.
- Reply preview bar / edit mode in composer: ABSENT.

## 5. Media viewing
- Image viewer: ABSENT. Video player: ABSENT. Save/share: ABSENT. Media gallery per chat: ABSENT; chat info shows only a non-clickable COUNT of photos+files (ChatInfoScreen.kt "Media, files and links" row; links are not counted).

## 6. Contacts and finding people
- Contacts tab (alphabetised sections, Collator-aware, tap opens/creates chat): W. A/ui/contacts/ContactsScreen.kt
- Add by username: W (server lookup, addContact). AddContactScreen.kt
- Search contacts: ABSENT.
- QR codes for contacts: ABSENT. QR exists only for device linking and server-invite scanning (A/ui/qr/*). AddContactScreen comment says QR "arrives later".
- Invite links: P. "Invite" row only shown if graph.inviteUrl != null; live build returns null (AppGraph.kt: "none in the live build until the app can make real invites"). So hidden in live. Sample-only.
- Message requests: P. List and open W; no Accept / Decline buttons: ChatStore.accept() is never called from A/ (grepped). Block from chat info works.
- Blocking: W. Chat info > Block (confirm) -> store.block, server block; Settings > Privacy > Blocked list (view only; NO unblock).
- Reporting: W. Chat info > Report -> queued report with last lines to server (pending-report retry). No reason picker.
- Safety-number verification: W when MLS engine gives real member keys. 12x5 digits sheet + "Mark verified" toggle (local). No QR/compare-by-scan. ChatInfoScreen.kt
- Find-me privacy toggles (by username / by email): STUB. Stored in in-memory settings only; nothing sends them to the server (grepped findByUsername/findByEmail: only Settings UI + model).

## 7. Groups
- ABSENT entirely: no group chat creation, members, roles, avatar/description, leave. Chat model is 1:1 (Chat.contactId); UI and store have no group concept. (MLS "group" words in code are the crypto group per 1:1 conversation.)

## 8. Chat info / profile (A/ui/chatinfo/ChatInfoScreen.kt)
- Header: initial avatar, name, @username: W. No photos, no bio, no profile editing of own profile (Settings header shows own name+username read-only).
- Audio / Video buttons: W (start live call).
- Mute button: STUB. Toggles a rememberSaveable boolean only; no effect on notifications, lost on leaving.
- Search button: STUB (`onSearch = {}`).
- Media/files count row: P (non-clickable).
- Encryption line (only place it appears, per rules): W. Verified seal + Verify sheet: W (see 6). Members-unreadable warning, mode mismatch warning: W.
- Disappearing messages row: STUB. Always shows "Off"; not clickable. (Settings > Privacy has a disappearing-messages picker that is also only stored in memory; no expiry logic in messaging/store, grepped.)
- Report / Block: W.
- No shared groups, no nickname, no clear history, no delete chat, no export.

## 9. Calls
- Audio + video 1:1 call engine: P (real stack, partial UI). LiveCallEngine (calls-android) + CallMachine state machine + WebRtcMediaSession on the project's own libwebrtc AAR; signalling via live socket, sealed in the 1:1 MLS chat; TURN creds from server. Whether it works end to end I could not verify without running; unit tests exist in calls/ and calls-android/.
- Outgoing: buttons in chat header, chat info, call-history row: W. Incoming push-ring -> Telecom (Connection service, with PlainCallController fallback) + full-screen intent + ringtone: W code (calls-android/.../telecom/*, IncomingCallActivity, Ringer, LockScreenCallActivity in app).
- Incoming call UI: W (IncomingCallScreen.kt, lock-screen activity, notification with Answer/Decline).
- In-call controls: mute W, speaker W, end W, data-mode sheet W (Auto/Data saver/etc.), minimise/banner W. Video toggle: P (see next).
- Video rendering: STUB. VideoStage() in A/ui/call/CallScreen.kt is a placeholder (person icon + grey gradient box); no SurfaceViewRenderer/VideoSink anywhere in app or calls-android (grepped). Camera capture + encoding to peer exists in WebRtcMediaSession, so the peer may see you but you never see them or yourself.
- Flip camera: STUB (`onFlip = {}`, `canFlipCamera=false` default).
- Camera permission for video: ABSENT at runtime (onboarding asks mic + notifications only; camera is requested only by the QR scanner).
- Call history tab: P. Built from call messages in chats (so limited to cached last 50 messages/chat); All/Missed filter, call-back button W. Not a dedicated call log table.
- Quality/data selection: W. Settings > Data and storage: call data mode (Auto/fixed), mobile limit, video-off-on-mobile; QualityAdaptor/VideoLadder/SdpMunger in calls/policy; per-call sheet. Quality pill on call screen shows only Good vs weak (3 vs 1 bars).
- Noise suppression: W. Settings > Calls and audio: Standard / Enhanced (RNNoise in capture hook) / Off. calls-android/.../media/Rnnoise.kt
- Network change handling: P/uncertain. CallMachine has ReconnectPolicy + ICE restart + "Reconnecting" banner on connection loss. But nothing observes connectivity changes and calls engine.setNetwork (no NetworkCallback in app; AppGraph.network is read on demand), so Wi-Fi<->mobile switch only helps via ICE failure detection.
- Sample call simulator (SimulatedCallEngine, "Simulate network" menu): only in sample mode or debuggable+callsAvailable (callsAvailable is always true, so the simulate menu shows in debug live builds only if engine is Simulated; live engine is SessionCallEngine so menu is effectively sample-only).
- Group calls: ABSENT. Call recording to chat as system message: W.

## 10. Notifications (push/ module; A/live/PushWiring.kt)
- Push delivery: W code. FCM (needs Firebase config; FirebaseInit) and UnifiedPush, registered to server with HPKE-sealed token via push gateway; inbox fetch worker; missed-push monitor. Message push is decrypted on device (NotificationProcessor) and shows sender name + preview. Generic fallback "new message" if it cannot decrypt.
- Notification channels: Messages, Requests, Devices, calls. Missed-call notice: W. New-own-device notice: W.
- Grouping/MessagingStyle/bundled per chat: ABSENT (one notification per chat tag, plain title/text; no summary, no history lines).
- Reply from notification / mark-as-read action: ABSENT (no RemoteInput or actions on message notifications; only call notifications have Answer/Decline).
- Tap notification opens that chat: STUB. MessageNotifier puts EXTRA_CHAT_ID on the launch intent but MainActivity never reads it (grepped): tap opens the app on the chat list.
- Suppress when chat is open / app foreground: not checked, no code seen.
- Mute options (per chat, DND schedule): ABSENT. Settings > Notifications has only delivery choice Google/UnifiedPush (radio) and a full-screen-intent permission shortcut.
- Delivery radio effect: STUB. SettingsStore.delivery is in-memory sample ("until the push settings are wired"); transport is actually chosen automatically by PushTransport.choose in PushWiring.registerAfterSignIn. The radio does not change it.
- App icon badge counts: ABSENT (no setNumber/badge code; launcher default dot only).

## 11. Settings (A/ui/settings/*; 8 rows)
- IMPORTANT: all settings live in an in-memory StateFlow (SettingsStore.kt, "until the synced store exists"). They reset to defaults on every app restart. No SharedPreferences/DataStore.
- Header: own name + @username, read-only. No avatar change, no name edit, no username change.
- Server: P. Shows host and a fixed "Connected" text (not live). ServerScreen in SettingsScreen.kt. No switch server / log out.
- Linked devices: W. List with last-seen, remove (confirm), "Link a device" scans QR + biometric approve; auto-refreshes on device.revoked. LinkedDevicesScreen.kt
- Privacy: P. IP-hiding for calls (W: feeds RelayDecision/ call policy). Read receipts (W: Outbox+Receipts). Find by username/email (STUB, never sent to server). Disappearing messages default (STUB, no enforcement). Blocked list (view only, count shown; no unblock).
- Data and storage: W for call data and auto-download; P in that nothing persists, and no storage-usage/clear-cache UI.
- Notifications: P (see 10; radio is STUB).
- Chat folders: W (persisted in DB).
- Calls and audio: W (noise suppression).
- Licenses / open-source notices: W (LicensesScreen.kt, generated).
- Missing vs mature apps: theme/dark mode toggle, language, text size, chat wallpaper, app lock/passcode (BiometricGate only used for link approval), backup/export, storage management, about/version, sign out, delete account, help/feedback.

## 12. Offline and reliability
- Local DB: W. Room + SQLCipher with key from Keystore-backed secret store; FTS5 on folded text; messages, attachments, reactions, contacts, folders, outbox. store/*
- Offline send queue: W. Outbox rows persist, backoff 1s doubling to 300s cap, MAX_ATTEMPTS then "failed", EscrowFailed/ModeMismatch are terminal, idempotent via clientMsgId, stale-epoch retry. messaging/.../send/Outbox.kt. UI never shows failed state or retry (see 3).
- Reconnect/backoff of live socket: W. LiveConnection with jittered exponential Backoff, 25 s OkHttp ping plus app-level ping probe. net/.../live/*. Socket is open only while app visible or in call (AppGraph.updateSocket); push wakes it otherwise.
- Sync after reconnect: W. Own-device watcher runs inbox sync + reconcile on each connect (OwnDeviceWatcher.kt); inbox cursor/ack.
- Connection state shown to user: ABSENT (see 2).
- Pagination/older messages: ABSENT in UI (see 3).
- Process death during open: W. Session opens lazily; pending creates/wipes resumed (LiveSession.start, LocalData crash-safe wipe).
- Multi-device: W for linked devices (MLS per device).
- Account unavailable (keystore unreadable) screen: W (AccountUnavailableScreen.kt).

## 13. Look and polish
- Theme: W. Material 3, system dark/light, Android 12+ dynamic colour (ui/theme/Theme.kt). No in-app theme choice, no accent/wallpaper options.
- Avatars: initial-letter circles only (ui/Avatar.kt). No photos, no colour-per-contact.
- Animations: minimal defaults (call banner fade, Material ripples). No message animations, no transitions customised.
- Accessibility: P/good basics. contentDescriptions on icons and ticks, Role.Button on clickables, merged semantics on rows/quotes, heading roles in contacts. Not audited beyond reading code.
- Localisation: P. All strings in res/values/strings.xml (~347 strings, English only; no translated locale folders). Locale-aware number/date helpers, RTL handling for usernames exist.
- Tablet/landscape: ABSENT. Single-pane phone layouts, no WindowSizeClass, no two-pane; rotation retained via rememberSaveable/ViewModels.
- Edge-to-edge: W. Icons: custom vector set. Splash: default.

## 14. Anything else notable
- Strong, unusual backend: end-to-end encryption (MLS via Rust FFI), server-escrow mode awareness, safety numbers, push-gateway with sealed tokens, link/recovery by passkey, local wipe on revocation, project-built WebRTC with RNNoise, Telecom integration. This part is ahead of what the UI exposes.
- `webrtc/` and `calls/` and `core/` and `messaging/` each keep a Placeholder.kt (empty marker objects), harmless.
- Sample data still ships: SampleChatStore/SampleAccountService/SimulatedCallEngine are in the main source set (core/), used by UI tests via `sampleData` intent extra, not by the live path.
- Tests: ~144 test files (unit + instrumentation smoke) cover logic; UI features above are not tested because they do not exist.
- Text-only 1:1 chats, no groups, no channels, no stickers/GIFs/emoji picker, no stories, no location, no polls, no bots.
- No chat export, no backup, no "delete chat" or "clear history".

## Counts by status (features listed above, my own tally; some counted once at their most specific line)
- WORKING: ~54
- PARTIAL: ~20
- SAMPLE-ONLY: 2 (Invite link row, call simulator)
- STUB (UI present, no action or no effect): 13 (new-chat FAB, attach sheet, mic/voice record, voice play, chat-info Mute, chat-info Search, disappearing messages, video rendering, flip camera, find-me toggles, notification-delivery radio, notification tap-to-open-chat, connection-status dot)
- ABSENT: ~45 (groups entirely, pin/archive/mute, chat/message search UI, swipe actions, forward/copy/select, date separators, grouping, unread marker, scroll-to-bottom, link previews, formatting, mentions, pagination, image/video viewer, gallery, QR contacts, account deletion, sign out, notification grouping/reply/badges, tablet layout, typing/online, etc.)
