# People, Client Stage: Implementation Plan

> **For agentic workers:** each task is built by one agent on its own branch, test-first, with a commit per step and a push after each commit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Typing and presence, disappearing messages, captions and link previews, profile name and photo that stay current, and adding a person by QR code or one-time link, on Android, iPhone and Mac.

**Architecture:** One foundation task per platform (P1) adds every new envelope kind, field, store column and server call, with shared wire vectors, and no screen. The five feature tasks (P2–P6) then build on it in parallel and touch different screens. Decisions that can be pure functions live in the shared core (`apps/android/core`, `apps/apple/Packages/AppCore`) with the same test table on both platforms.

**Tech Stack:** Kotlin/Compose; Swift 6/SwiftUI. System pickers, camera, code scanner and share sheet only.

**Spec:** `docs/superpowers/specs/2026-11-18-people-and-groups-design.md` sections 6.1–6.5 (section numbers below refer to it). Server contract: `docs/superpowers/specs/2026-10-27-server-design.md`. Read both and AGENTS.md first.

## Global Constraints

- No new dependencies. No change to `mlsffi`, key handling, the push gateway or `.github/workflows`. If a step needs one, stop and report.
- Wire names are exact: envelope kinds `typing`, `presence`, `timer`, `profile`; fields `on`, `state`, `seconds`, `ttl`, `c`, `lp`, `name`, `photo`, `ver`; frame `conv.ephemeral`; request field `expiresAt`.
- A kind or field a client does not know must keep behaving as today (unknown kind → unsupported row; unknown field ignored). The new kinds `typing`, `presence`, `profile` are never stored as messages and never notify; `timer` is stored as a system line and never notifies.
- Exact UI strings from the spec, in `Localizable.xcstrings` / `strings.xml`. Plain words; never "codec", "kbps", "relay", "MLS", "E2EE". No encryption labels outside chat info.
- A label for every control; leading/trailing layout; system components, SF Symbols / Material icons already in the build, system text styles.
- App lock: nothing new shows chat content, a name from a chat, or a typing line while the lock cover is up. The scanner and the photo picker use the existing "expected return" allowance.
- Commits: plain messages, no attribution trailers. Push after each commit.
- Heavy commands only as `flock /tmp/myapp-heavy.lock timeout 900 <command>`; never two at once; bounded waits in tests. Kill your own background jobs before the final report.
- Android: `ANDROID_HOME=/home/user/android/sdk JAVA_HOME=/home/user/android/jdk-17`, Gradle with `--offline -PwebrtcAar=/home/user/code/myApp/.claude/worktrees/agent-aee95885a0b3e7736/apps/android/webrtc/build/webrtc-download/libwebrtc.aar -PwebrtcAarSha256=8194d49d5ca6f11d4ca51d1cfc1d3733cc983fd5df557d1ec40cd79ec6819f55`; skip `:mls` tests; run `checkAppId` and `:app:lintDebug` before the final report.
- Apple: packages test on Linux with `~/.local/share/swiftly/bin/swift test` in the package folder. App targets cannot be compiled locally: read every app-side change for Swift 6 strict-concurrency and `#if os(...)` errors and list uncompiled files in the report. An identifier on a container hides its children's identifiers unless the container is `.accessibilityElement(children: .contain)`; a row reads as "Label, value" (match `label CONTAINS`); tap a form switch at its trailing edge; `waitToExist` before tapping.
- Store migrations take the next free version number on `main` at the time of building; P1 owns the only migration of this stage.

## Review Focus

1. A `typing`, `presence` or `profile` envelope that arrives as an ordinary stored message (not the ephemeral frame), or one with a bad field: no chat row, no notification, no crash.
2. A message whose `at + ttl` is already past when it is decrypted (device was offline): never shown, never notified; its attachment is not downloaded.
3. A `profile` with a lower or equal `ver`, a 25 KiB photo, or a name that fails `DisplayNameRule`: ignored whole; the stored profile is unchanged.
4. A link-preview fetch aimed at `http://`, a private, loopback or link-local address (including through a redirect or a DNS name that resolves to one), or a 10 MiB page: refused, nothing sent, the message still sends without a preview.
5. A `/c/<token>` or `/u/<name>` link for another host, with extra path or query, or opened while locked or signed out: no request to a foreign host; handled after unlock or onboarding, once.

Each task below owns the test for the lines that concern it.

---

### Task P1 (Android `feat/p1-android-people-base`, Apple `feat/p1-apple-people-base`): wire, stores, server calls

No screens. Everything later tasks consume.

**Produces — envelope** (`ContentEnvelope` on both platforms, plus shared vectors `server/internal/vectors/envelope_people.json` read by a Swift test and a Kotlin test; cases `{json, kind, fields}` and `{json, unsupported}`):
- `typing {on: bool}`; `presence {state}` where state is `online` or `away`, anything else invalid.
- `timer {seconds}`: integer, one of 0, 86400, 604800, 7776000; anything else invalid.
- `profile {name, photo?, ver}`: `name` passes `DisplayNameRule.clean` unchanged, `photo` base64 JPEG ≤ 24,576 bytes decoded (starts FF D8), `ver` ≥ 1.
- Optional `ttl` (integer seconds, 1…31,622,400) on `text`, `photo`, `voice`, `file`; out of range → the field is ignored and the message is kept.
- Optional `c` on `photo` and `file` (text-message length rule; over-long → truncated to the limit on receive, refused on send).
- Optional `lp {url, title, desc?, img?}` on `text`: `url` https and present in the text; `title` 1…200, `desc` ≤ 300 characters; `img` JPEG ≤ 8,192 bytes; an invalid `lp` is dropped and the text kept.

**Produces — core (pure, same tests both platforms):**
- `Expiry.expiresAt(atMs, ttlSeconds) -> ms?` and `Expiry.isExpired(expiresAtMs?, nowMs) -> Bool` (expired when `now >= expiresAt`).
- `TimerChoice`: `off, day, week, quarter` with `seconds` 0 / 86400 / 604800 / 7776000 and `from(seconds)`.
- `ProfileMerge.accept(current: ver?, incoming: ver) -> Bool` (strictly higher only).

**Produces — store** (one migration; Apple `LocalStore`, Android Room):
- `messages.expires_at` (ms, null), `messages.caption` (null), `messages.link_preview` (JSON, null); `chats.timer_seconds` (default 0).
- Table `profiles(account_id primary key, name, photo blob null, ver)`; own profile kept in the existing settings store as `profile.ver` and `profile.photo`.
- Repository/`ChatStore` API (same names both platforms, platform casing): `setTimer(chatID, seconds)`, `timerSeconds(chatID)`, `sweepExpired(now) -> [removed ids]` (removes rows, attachment files, search entries, in one transaction, returns ids for notification removal), `profile(for accountID)`, `saveProfile(accountID, name, photo, ver) -> Bool`, `captionSearch` (the caption is indexed by the existing full-text search).
- `ChatStore` send path: when `timerSeconds(chat) > 0`, every `text/photo/voice/file` it builds carries `ttl`, stores `expires_at`, and the HTTP send carries `expiresAt`.
- Incoming: `timer` updates `chats.timer_seconds` and inserts a system line (kind `timer`, with seconds and author); `profile` goes through `ProfileMerge` then `saveProfile`; `typing`/`presence` arriving as stored messages are dropped; a message already expired on arrival is dropped before insert and before attachment download.
- The notification extension / Android notification path stores `expires_at` and skips an already expired message.

**Produces — server calls** (`ServerKit` / `net`): `sendEphemeral(conversationID, epoch, ciphertext)` and an incoming `conv.ephemeral` frame event `{conversationID, epoch, from: {account, device}, ciphertext}`; `createContactLink() -> {token, expiresAt}`; `redeemContactLink(token) -> {accountID, username, displayName}` with errors `own_link`, `expired`, `too_many`, rate limited; `expiresAt` on message send.

**Produces — signal channel** (`Messaging`; revised, see spec 6.1): `EphemeralChannel.send(chatID, body)` encodes a `typing`/`presence` envelope as JSON bytes and calls `sendEphemeral` with the chat's conversation ID and current epoch; `EphemeralChannel.events` yields `(chatID, accountID, body)` for incoming frames. No MLS call is made on either side and nothing is stored. An incoming frame is dropped silently when: the conversation is unknown; `from.account` is not the other member of that chat (or a current member of that group) or is this account; the payload is over 256 bytes, is not valid JSON, or is any kind other than a valid `typing` or `presence`.

- [ ] Shared vectors file and failing envelope tests on both platforms; implement; pass.
- [ ] Failing tests for `Expiry`, `TimerChoice`, `ProfileMerge`; implement; pass.
- [ ] Migration with a test that opens a database at the previous version with rows and migrates.
- [ ] Store tests on the real store with the existing fakes: timer set → next send carries `ttl` and `expiresAt`; `sweepExpired` removes row, file and search hit; expired-on-arrival dropped; `timer` system line; `profile` accept/ignore (Review Focus 3); stray `typing` stored message dropped (Review Focus 1).
- [ ] Server-call tests against the existing fake transport, with the exact JSON from the server design.
- [ ] `EphemeralChannel` tests: round trip; a frame from an account that is not in the chat dropped; own account dropped; a `text` envelope, bad JSON and a 257-byte payload dropped; no MLS engine call on send or receive (assert on the fake).

### Task P2: Typing and presence (spec 6.1)

Branches `feat/p2-android-typing`, `feat/p2-apple-typing`. Consumes `EphemeralChannel`.

**Produces (core, pure):** `TypingSender.step(state, event, nowMs) -> (state, send: Bool?)` with events `textChanged(isEmpty)`, `sent`, `tick`: sends `on: true` at start and every 8,000 ms while typing, `on: false` on `sent`, on empty text, or after 5,000 ms idle. `TypingShown.isTyping(lastOnMs?, nowMs)`: true for 10,000 ms after the last `on: true`; cleared by `on: false` or an incoming message. `PresenceLine.text(lastOnlineMs?, lastWitnessedMs?, nowMs) -> online | lastSeen(ms) | none`: online for 90,000 ms after the last `online`; `away` ends online at once.

- [ ] Failing tests for the three functions (boundaries at 7,999/8,000, 4,999/5,000, 9,999/10,000, 89,999/90,000 ms; `away`; nothing witnessed → none); implement; pass.
- [ ] Settings > Privacy: switches "Typing indicator" and "Online and last seen" with the spec's footers, default on. Off: nothing sent, nothing shown (test at the model level with a fake channel).
- [ ] Composer drives `TypingSender`; chat header shows "typing…" else the presence line; chat list row shows "typing…" in place of the preview. Presence sender: `online` on foreground and every 60 s to open one-to-one chats, `away` on background. Blocked contacts: never sent to, never shown.
- [ ] `lastWitnessedMs` is kept per contact in memory and in the store's existing contact row or settings (no new migration; if one is needed, stop and report).
- [ ] UI test (sample mode, a scripted fake channel): the sample chat shows "typing…" in the header and the list, then the message; the Privacy switches exist.

### Task P3: Disappearing messages (spec 6.2)

Branches `feat/p3-android-timers`, `feat/p3-apple-timers`. Consumes `setTimer`, `sweepExpired`, `TimerChoice`, `Expiry`.

- [ ] Chat info row "Disappearing messages" with the current choice, opening Off / 1 day / 7 days / 90 days and the explanation text; choosing sends `timer` and writes the system line. Failing model test first: choosing 7 days sends one `timer {604800}` and the next text carries `ttl 604800`.
- [ ] System lines with the spec's three strings (own, other's, turned off), durations worded "1 day", "7 days", "90 days".
- [ ] Sweeper: runs at start, on foreground, and by a timer set to the next `expires_at` while the app is open; removes delivered notifications for removed ids; the open chat updates without a reload. Test with a fake clock: a message disappears at its second, not before; two devices' views use `at + ttl`, not arrival time.
- [ ] Bubble timer glyph (`timer` SF Symbol / the Material timer icon already in the build) with label "Disappearing message".
- [ ] UI test (sample mode): set 1 day in chat info, the system line appears, the row shows "1 day".

### Task P4: Captions and link previews (spec 6.3)

Branches `feat/p4-android-captions`, `feat/p4-apple-captions`. Consumes `c`, `lp`.

**Produces (core, pure):** `LinkPreviewRule.firstLink(text) -> url?` (first https link, using `MessageFormat`'s link detection); `LinkPreviewRule.allowedHost(host, resolved: [address]) -> Bool` (refuses IP-literal hosts, loopback, private, link-local, unique-local, CGNAT, multicast, unspecified, and names ending `.local`, `.internal`, `localhost`); `LinkPreviewParse.fromHTML(bytes, limit) -> {title, desc?, imageURL?}` reading `og:title`, `og:description`, `og:image`, falling back to `<title>`; trims to 200/300 characters.

- [ ] Failing tests for the three functions, same table both platforms (including `https://127.0.0.1`, `https://[::1]`, `https://10.0.0.1`, `https://169.254.169.254`, a name resolving to `192.168.1.1`, `http://`, a link inside `` `code` ``, malformed HTML, a title of 500 characters); implement; pass.
- [ ] `SendPlan` change: the caption goes in `c` of the first item and the following text message is no longer sent. Update the `SendPlan` tests; receive path shows the caption under the media, formatted, searchable; edit of a captioned message edits the caption.
- [ ] Fetcher: system HTTP client, HTTPS only, 5 s total, body cap 524,288 bytes, no cookies, no credentials, redirects followed only to the same host and re-checked by `allowedHost`, the resolved addresses checked before connecting where the platform allows (state in the report what each platform can guarantee). Image fetched under the same rules, re-encoded to JPEG ≤ 8,192 bytes with the system encoder or dropped. Tests with a fake transport for each refusal (Review Focus 4).
- [ ] Settings > Privacy "Link previews" (default off) with the spec's footer. Composer preview card with a remove (×) control "Remove preview"; removed stays removed for that draft. Receivers render `lp` from the message and never fetch (test: rendering makes no network call).
- [ ] UI test (sample mode, canned preview): the sample message shows the preview card; the Privacy switch exists and is off.

### Task P5: Profile photo and current names (spec 6.4)

Branches `feat/p5-android-profile`, `feat/p5-apple-profile`. Consumes `profile`, `saveProfile`, `ProfileMerge`.

- [ ] `ProfileSender`: on a name or photo change, bumps `profile.ver` and sends `profile` to every one-to-one conversation; sends it once to a conversation when it is created or first joined; resend is idempotent by `ver`. Failing model tests first (3 chats → 3 sends; no change → no send; blocked contact → no send).
- [ ] Photo preparation as a pure-ish function `ProfilePhoto.prepare(imageData) -> jpeg?`: centre-crop square, 256×256, JPEG quality stepped down until ≤ 24,576 bytes, else nil. Test with fixtures.
- [ ] Settings > profile: "Change photo" (system photo picker and camera), "Remove photo"; the tile shows the photo.
- [ ] Everywhere a contact's name or tile is shown (chat list, chat header, chat info, contacts, notifications, call screens): the stored profile's name (after `DisplayNameRule.forDisplay`) and photo, falling back to the lookup name and the coloured letter. One helper per platform; no screen reads the profile table directly.
- [ ] UI test (sample mode): the sample contact with a sample photo shows it in the chat list and chat info; the profile screen shows "Change photo".

### Task P6: QR code and links (spec 6.5)

Branches `feat/p6-android-links`, `feat/p6-apple-links`. Consumes `createContactLink`, `redeemContactLink`.

**Produces (core, pure):** `ContactLink.parse(url, serverHost) -> user(username) | invite(token) | otherServer | notALink`: scheme https only, host equal to `serverHost` ignoring case, path exactly `/u/<username>` (the username rule already in core) or `/c/<token>` (22 URL-safe base64 characters), no extra path segments; a query or fragment is ignored.

- [ ] Failing tests, same table both platforms (other host, `http`, `/u/` with a bad username, `/c/` with 21 and 23 characters, trailing slash, extra segment, upper-case host); implement; pass.
- [ ] "My QR code" screen (Settings > profile and add contact): the code for `https://<host>/u/<username>` drawn with the system generator (Core Image on Apple; on Android only if a QR writer is already in the build, otherwise stop and report for that screen only), the username under it, Share.
- [ ] Scanner: the system scanner (`DataScannerViewController` on iPhone; Android only with what is already in the build, else the screen offers "Paste link" only and the report says so). Mac: paste and open-link only.
- [ ] "Invite with a link": creates the link, opens the system share sheet with the URL; `too_many` → "You have too many unused links. Wait for one to expire."
- [ ] Opening a link (app link / universal link, paste, scan): `user` → the add-contact confirmation for that username; `invite` → redeem, then the same confirmation; own link, expired, another server and already-a-contact per the spec's messages. A link opened while locked or signed out is kept (one pending link, the latest wins) and handled once after unlock or onboarding (Review Focus 5; model tests).
- [ ] The token is never logged, never put in analytics or the pasteboard by the app, and is dropped from memory after redeem.
- [ ] UI test (sample mode): "My QR code" shows the code image and the username; opening a sample `/u/` link shows the add-contact confirmation.

## Merge

The lead merges. P1 first on each platform (Opus review: wire, expiry, the ephemeral channel). P2–P6 start from the merged P1 and merge as they pass review: Android one by one after local tests; Apple in one push, then CI, then fix forward. P4's fetcher and P6's link handling each get an Opus review; the lead reviews every screen. No task is called passing on Apple without a green run.
