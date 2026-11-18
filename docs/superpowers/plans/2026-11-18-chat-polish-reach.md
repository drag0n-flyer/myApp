# Chat Polish Part 2 and Reach Implementation Plan

> **For agentic workers:** each task is built by one agent on its own branch, test-first, with a commit per step and a push after each commit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold-to-record voice notes, send preview with caption, upload cancel, video, viewer and gallery, text formatting, in-chat search, and wide-screen, landscape and keyboard support on Android, iPhone and Mac.

**Architecture:** Every decision that can be a pure function lives in the shared core (`apps/android/core`, `apps/apple/Packages/AppCore`) with the same test table on both platforms; screens call it. No change to the content envelope, the server API, stored keys or the build's dependencies.

**Tech Stack:** Kotlin/Compose; Swift 6/SwiftUI. System pickers, players and haptics only.

**Spec:** `docs/superpowers/specs/2026-11-18-chat-polish-reach-design.md` (section numbers below refer to it). Read it and AGENTS.md first.

## Global Constraints

- No new dependencies. No change to the content envelope, server API, database key handling or cryptography. If a step needs one, stop and report.
- Exact UI strings from the spec, in `Localizable.xcstrings` / `strings.xml`. Plain words; never "codec", "kbps", "relay", "MLS", "E2EE".
- A label for every control; leading/trailing layout; system components, SF Symbols / Material icons already in the build, system text styles.
- Commits: plain messages, no attribution trailers. Push after each commit.
- Heavy commands only as `flock /tmp/myapp-heavy.lock timeout 900 <command>`; never two at once; bounded waits in tests.
- Android: `ANDROID_HOME=/home/user/android/sdk JAVA_HOME=/home/user/android/jdk-17`, Gradle with `--offline -PwebrtcAar=/home/user/code/myApp/.claude/worktrees/agent-aee95885a0b3e7736/apps/android/webrtc/build/webrtc-download/libwebrtc.aar -PwebrtcAarSha256=8194d49d5ca6f11d4ca51d1cfc1d3733cc983fd5df557d1ec40cd79ec6819f55`; skip `:mls` tests; run `checkAppId` and `:app:lintDebug` before the final report; never write the application ID literal outside `app-id.properties`. Stop Gradle daemons only under the lock.
- Apple: packages test on Linux with `~/.local/share/swiftly/bin/swift test` in the package folder. The app targets cannot be compiled locally: read every app-side change for Swift 6 strict-concurrency and `#if os(...)` errors, and say in the report which files are uncompiled. State that is iPhone-only must not be read in code the Mac compiles.
- An accessibility identifier on a container hides its children's identifiers from UI tests unless the container is `.accessibilityElement(children: .contain)`. A row that is one element reads as "Label, value": UI tests match with `label CONTAINS`. A switch in a form row is tapped at its trailing edge.
- App lock: nothing new may show chat content while `lock.coversContent` (Apple) / the lock cover is up (Android).

## Review Focus

1. A voice note released at 0.9 s, or after sliding past the cancel distance and back, is not sent; the file is gone.
2. Cancel upload at 99%: either the message is gone on both sides or it is sent; never a stuck row, never a blob left behind.
3. `2*3*4`, `snake_case_name`, an unclosed `*`, and markers inside `` `code` `` render as typed; copy returns the typed text.
4. Rotation or a split-view resize during a recording, an upload, a call and with text in the composer loses nothing.
5. A Mac shortcut pressed while the app is locked does nothing.

Each task below owns the test for the lines that concern it.

---

### Task N8 / A8: Voice notes (spec 6.1)

Branches `feat/n8-android-voice`, `feat/a8-apple-voice`.

**Produces (core / AppCore):** `VoiceGesture` — a pure state function. Kotlin: `object VoiceGesture { fun step(state: State, event: Event, rtl: Boolean): State }`; Swift: `enum VoiceGesture { static func step(_ state: State, _ event: Event, rtl: Bool) -> State }`. States: `idle, pending, recording, willCancel, willLock, locked, preview, cancelled, send, tooShort`. Events: `press, heldFor(ms), drag(dx, dy), release(elapsedMs), stop, limitReached, interrupted, delete, sendTapped, activateAccessible`. Constants: `startDelayMs = 150`, `minimumMs = 1000`, `cancelDistance = 100`, `lockDistance = 70`.

- [ ] Write the failing `VoiceGesture` tests, the same table on both platforms: release at 999 ms → `tooShort`, at 1000 ms → `send`; drag dx −100 (LTR) → `willCancel`, release there → `cancelled`; back to −99 → `recording`; RTL mirrors (dx +100); dy −70 → `willLock`, release → `locked`; cancel wins when both distances are passed; `locked` + `stop` → `preview`; `limitReached` in recording or locked → `preview`, never `send`; `interrupted` → `preview`; `activateAccessible` from idle → `locked`; `delete` from locked or preview → `cancelled`; release before 150 ms → `tooShort`.
- [ ] Implement `VoiceGesture`; tests pass.
- [ ] Recording bar, locked bar and preview in the composer, driven by `VoiceGesture`; live level meter from the recorder's input level (add a level read-out to the existing recorder; no new library); haptics; strings.
- [ ] Recorder file is deleted on `cancelled` and `tooShort`; orphan recordings are removed at start. Test with the existing recorder fakes.
- [ ] Mac and accessibility activation start a locked recording.
- [ ] UI test (sample mode, fake recorder): accessible activation → locked bar → Stop → preview → Send adds a voice message; Delete adds none.

### Task N9 / A9: Media (spec 6.2)

Branches `feat/n9-android-media2`, `feat/a9-apple-media2`.

**Produces (core / AppCore):** `SendPlan` — `fun plan(items: List<Picked>, caption: String, limitBytes: Long): Result` returning the ordered sends (attachments, then the caption as text after the first item, trimmed, omitted when blank) or the refusal (`tooMany` over 10, `videoTooBig`); `GalleryGroups.byMonth(items, zone)`; `UploadCancel` rule: `fun onCancel(phase): Action` → `removeUnsent` while queued or uploading, `keep` once sent.

- [ ] Failing tests for the three pure pieces with these cases: 3 photos + caption " hi " → photo, text "hi", photo, photo; blank caption → no text; 11 items → `tooMany`; a video one byte over the limit → `videoTooBig`; month grouping across a year boundary and a time-zone edge; cancel in each phase.
- [ ] Implement; tests pass.
- [ ] Store: `cancelUpload(messageId)` stops the upload, removes the queued message and its staged files in one transaction; a cancel after completion changes nothing. Tests on the real store with the existing fake uploader: cancel while uploading → no message, no blob, nothing delivered; cancel after sent → message stays.
- [ ] Upload progress exposed per message (fraction) from the existing upload path; bubble ring with Cancel.
- [ ] Preview screen with caption; pickers allow videos; over-limit refusal before staging.
- [ ] Video bubble (thumbnail from the local file, duration), viewer over the chat's local media with swipe, zoom, system video controls, Share and Save.
- [ ] Chat info "Media and files" with the two tabs and empty states.
- [ ] UI test (sample mode): preview shows the caption field and Send; gallery opens from chat info and shows the sample photo; cancel on a sample upload removes the row.

### Task N10 / A10: Text (spec 6.3)

Branches `feat/n10-android-text`, `feat/a10-apple-text`.

**Produces (core / AppCore):** `MessageFormat.parse(text): List<Span>` where `Span(range, styles: Set<Style>)` with `Style = bold | italic | strike | mono | block`, and `MessageFormat.plain(text): String`.

- [ ] Failing tests, the same table on both platforms (input → plain / styled ranges): `*a*` bold; `_a_` italic; `~a~` strike; `` `a*b*` `` mono only; `*a _b_ c*` nested; `2*3*4`, `snake_case_name`, `a * b * c`, `*a` and `*a\nb*` unformatted; `(*a*)` and `*a*,` formatted; a three-backtick block across lines keeps its inner text verbatim; `*a* https://x.example/_b_` keeps the link whole; empty `**` unformatted; emoji and combined characters keep correct ranges (count in the unit the platform's text type uses, and test it).
- [ ] Implement; tests pass.
- [ ] Bubbles render spans with system fonts (monospaced system font for mono); links stay tappable outside mono; chat list rows, notifications, reply quotes show `plain`. Copy/edit/forward keep the stored text (test at the store or view-model level).
- [ ] In-chat search: bar, "%1$d of %2$d", Previous/Next, jump and flash, paging older messages as needed, "No results". Search order and the index-of-current rule are a pure function `ChatSearch.step(results, current, direction)` with tests (wraps at the ends).
- [ ] UI test (sample mode): open search in a sample chat, type a word from the sample data, see "1 of N", Next moves.

### Task N11 / A11: Reach (spec 6.4)

Branches `feat/n11-android-reach`, `feat/a11-apple-reach`.

**Produces (core / AppCore):** `PaneLayout.forWidth(dp|points): single | twoPane` (Android threshold 840); Apple: `MainCommand` — the list of menu commands with `isEnabled(locked:signedIn:hasOpenChat:lockOn:)`.

- [ ] Failing tests: 839 → single, 840 → twoPane; every `MainCommand` that shows or changes chat content is disabled when locked or signed out; Lock Now is enabled only when the lock is on and the app is unlocked; chat commands need an open chat.
- [ ] Implement; tests pass.
- [ ] Android: two-pane main screen at 840 dp, back handling, landscape for every screen, state kept across rotation (`rememberSaveable` / view models); remove any portrait lock from the manifest. Robolectric tests with qualifiers (`w900dp`, `land`) for the pane choice, and that the open chat and composer text survive a configuration change.
- [ ] iPhone/iPad: iPad in the device family with one scene; `NavigationSplitView` in regular width; orientations in `project.yml`. Mac: `Commands` built from `MainCommand`, each disabled by its rule, shortcuts from the spec.
- [ ] UI test: (Android) wide window shows "Select a chat" then the chat beside the list; (Mac) the New Chat menu item exists and opens the new-chat sheet; with the lock cover up the item is disabled.

## Merge

The lead merges. Order: N10/A10, N8/A8, N9/A9, N11/A11 (composer and chat screen conflicts are resolved at merge). Android branches merge as they pass review and local tests; Apple branches go to main in one push, then CI, then fix forward. No task is called passing on Apple without a green run.
