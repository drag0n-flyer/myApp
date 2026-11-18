# Daily use on iPhone and Mac Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the four "daily use" features that Android has (chat list actions and saved drafts, notifications, app lock, profile name and coloured tiles) to the iPhone and Mac apps, with the same behaviour.

**Architecture:** Rules live in pure Swift in `AppCore` (tested on Linux) and are the same rules as the Kotlin ones in `apps/android/core`. Storage changes go through `LocalStore`'s numbered migrations. SwiftUI views in `App/Features` stay thin. The Android implementation on `main` is the reference for every behaviour the design does not spell out.

**Tech Stack:** Swift 6, SwiftUI (iOS 17+, macOS 14+), packages `AppCore`, `LocalStore`, `Messaging`, the `NotificationService` extension, `LocalAuthentication` and `UserNotifications` from the system.

**Spec:** `docs/superpowers/specs/2026-11-18-daily-use-design.md` (sections 6 and 7 apply to every platform).

## Global Constraints

- All of `AGENTS.md`. No new dependencies. No change to cryptography, keys, MLS, the push gateway, the server, or WebRTC.
- Apple system components only; SF Symbols; system text styles; a VoiceOver label on every control; leading/trailing layout; every string in `App/Resources/Localizable.xcstrings` (`extractionState: manual`, English value equal to the key).
- Exact UI strings: design section 7. `[App]` is `AppBrand.name`. Never show "codec", "kbps", "relay", "MLS", "E2EE". No encryption labels in the chat list, chat or call screens.
- Per device: pin, mute and archive are not synced. A new message never unarchives, unmutes or unpins a chat. At most 5 pinned chats. Archiving a chat unpins it; an archived chat cannot be pinned.
- App lock default delay is 1 minute; choices 0, 60, 300, 3600 seconds; any other stored value counts as 60.
- With app lock on, no notification shows a sender, a caller name or message text.
- Display name: 1 to 40 code points after trimming, at least one visible character, no control characters, no line or paragraph separators, no direction or format controls. Names from other people are cleaned for display (`forDisplay`, cut to 64 code points, username as fallback).
- Tile colour index is `fnv1a32(UTF-8 of account ID) mod 8` (offset 2166136261, prime 16777619). Pinned values: `"a"` → 4, `"acc-ana"` → 5, `"3f2b8c1e-9d4a-4e7b-a1c5-0b6d7e8f9a12"` → 2, `""` → 5, `"é"` → 1.
- Tile colours (white letter), light / dark, index 0 to 7: B3261E / A8453F, A14A00 / 9B5A1A, 5C6B00 / 6B7A1F, 1B6E3A / 2F7D4A, 00696B / 0F7578, 1A5FB4 / 3F6FB5, 5B4BB8 / 7566C9, A8327A / B04A8E.
- Package tests run on Linux: `cd apps/apple/Packages/<Package> && flock /tmp/myapp-heavy.lock timeout 900 ~/.local/share/swiftly/bin/swift test`. App targets compile only in CI on `main`; write app code so it can be read for errors (Swift 6 strict concurrency, `@MainActor` views and models).
- Work test-first: failing test, see it fail, implement, see it pass, commit. Plain commit messages, no attribution trailers. Push only the task's branch.

## Review Focus

- A chat row written whole (read, copy, write) from the inbox, a media send, a call record or a device-link import must not reset `pinned_at`, `muted` or `archived`: every such write is a single-column update or runs inside the store's write transaction. Test in `LocalStore` and `Messaging`.
- A database written by the previous app version opens after the migration with every old row unchanged. Test with a populated previous-version database.
- A notification reply must go to the chat it was shown for, be sent at most once, and never vanish: sent, or kept as that chat's draft with "Not sent". Test the decision in `AppCore`.
- Nothing of a chat is on screen, in the app switcher snapshot, or in a notification while the app is locked, including after a cold start before settings are read (treat as locked).
- The placeholder name "You" is never offered as the person's name, saved to the server, or stored in the own contact row.

---

### Task A4: Chat list actions and saved drafts

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Chats/ChatListOrder.swift`, tests `ChatListOrderTests.swift`
- Modify: `AppCore` `Chat` model (add `pinnedAt: Date?`, `muted: Bool`, `archived: Bool`, `draft: String?`), `ChatStore` protocol and sample store
- Modify: `apps/apple/Packages/LocalStore/Sources/LocalStore/{Schema,Migrations,Records,Repository}.swift`, tests in `LocalStoreTests`
- Modify: `Messaging` `LiveChatStore`, tests in `MessagingTests`
- Modify: `apps/apple/App/Features/ChatList/{ChatListView,ChatRowView}.swift`; create `ArchiveView.swift`
- Modify: `App/Features/Chat/AppModel+Chat.swift` and the composer to read and write drafts through the store; remove `AppCore/Chats/DraftKeeper.swift` once nothing uses it
- Test: `apps/apple/AppUITests/iOS/ChatListActionsTests.swift`

**Interfaces:**
- Produces: `ChatListOrder.sorted(_ chats: [Chat]) -> [Chat]`, `ChatListOrder.maxPins = 5`; `ChatStore.setPinned(_ chatID: String, _ pinned: Bool) -> Bool` (false when the limit is reached or the chat is archived), `setMuted(_:_:)`, `setArchived(_:_:)`, `setDraft(_ chatID: String, _ text: String)`, `var mutedChatIDs: Set<String>`; `Repository.bumpActivity(chatID:at:)`, `bumpChat(chatID:unreadDelta:at:)`, `reduceUnread(chatID:by:)` as single statements.
- Consumes: the existing `ChatStore`, `LocalStore` migration list.

- [ ] **Step 1: `ChatListOrder` tests, then the rule.** Pinned chats first, newest pin on top; the rest by last activity; archived chats are left out of the main list; `maxPins` is 5. Mirror `apps/android/core/.../ChatListOrder.kt` and its tests.
- [ ] **Step 2: Migration tests, then the migration.** A new numbered migration adds `chats.pinned_at` (nullable integer), `chats.archived` (integer, not null, default 0), `chats.muted` if it does not exist yet, and a `drafts` table (`chat_id` primary key referencing `chats(id)` on delete cascade, `text`, `updated_at`). The test builds a populated database at the previous version from that version's schema, migrates, and asserts every old row is identical, `foreign_key_check` and `integrity_check` are clean, and a second open changes nothing.
- [ ] **Step 3: Repository and store tests, then the code.** Single-column writes for pin, mute, archive (archive also clears `pinned_at` in the same statement); the three bump functions above; drafts: blank text deletes the row, one row per chat, deleted with the chat and by sign-out. Convert every read-copy-write of a chat row outside a transaction. Tests: an incoming message, a media send and a call record leave pin, mute and archive unchanged; the sixth pin is refused, counting pins still waiting to be written; a draft typed in a chat with no row yet is written when the row appears; a draft set during a reload is not replaced by an older one.
- [ ] **Step 4: Views.** `ChatRowView`: pin and muted icons (`pin.fill`, `bell.slash.fill`) before the time, "Draft:" in red followed by the draft's first non-blank line in place of the preview, a neutral unread badge for a muted chat. `ChatListView`: system swipe actions (leading edge Pin or Unpin; trailing edge Archive, and Mute or Unmute), a context menu with Pin, Mute, Archive and "Mark as read" (only with unread messages), an "Archived" row at the top when there are archived chats (count, and the unread count in a neutral badge), the alert "You can pin up to 5 chats." `ArchiveView`: the archived chats, Unarchive by swipe or menu, no Pin, "No archived chats" when empty. Mac: the same actions in the row's context menu. Identifiers: `chatlist.archived`, `chatlist.row.<id>`, `chatlist.action.pin`, `.mute`, `.archive`, `.markread`, `archive.list`.
- [ ] **Step 5: Drafts through the store.** The composer loads the chat's draft on open and saves on every change; sending clears it only when the message was queued; edit mode does not write drafts.
- [ ] **Step 6: UI test** on sample data: archive a chat by its context menu, find it under "Archived", unarchive it; pin a chat and see it first.

### Task A5: Notifications

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Notifications/NotificationPlan.swift`, tests `NotificationPlanTests.swift`
- Modify: `apps/apple/App/Notifications/LocalDeviceNotifier.swift`, `apps/apple/NotificationService/*.swift` (content only: title, body, thread, category, sound; no change to decryption or keys)
- Create: `apps/apple/App/Notifications/NotificationActions.swift`

**Interfaces:**
- Produces: `NotificationPlan.content(chatID:sender:text:muted:appLockOn:) -> PlannedNotification` (title, body, `threadIdentifier` = chat ID, category `message` or `message.locked`, `silent: Bool`); `NotificationPlan.replyDecision(chatKnown:isRequest:isBlocked:textLength:appLockOn:) -> ReplyDecision` (`send`, `refuseKeepDraft`); `NotificationActions.handle(_ response: UNNotificationResponse) async`.
- Consumes: A4's `mutedChatIDs` and `setDraft` (until A4 is merged, read an empty set and keep a private in-memory draft behind one function named `saveReplyDraft`), A6's `appLockOn` (until merged, a closure returning false behind one named hook; unknown counts as locked).

- [ ] **Step 1: `NotificationPlan` tests, then the rule**, mirroring `apps/android/core/.../notify/NotificationPlan.kt`: one thread per chat; a muted chat's notification is silent and a missed call is never silent; with app lock on the title is the app name, the body is "New message" (or the missed-call text) and the category has no Reply; reply refused for a message request, a blocked or unknown chat, text over 4096 characters, or app lock on.
- [ ] **Step 2: Categories and posting.** Register `message` (actions "Reply" as a text input action and "Mark as read") and `message.locked` ("Mark as read" only). Every message notification, from the app and from the extension, sets `threadIdentifier`, the category, and no sound when silent. The extension reads the muted set and the lock setting from the shared app-group storage the app writes; when it cannot read them it hides content and keeps sound.
- [ ] **Step 3: Actions.** Reply: decide with `replyDecision`; send through the store's normal send path and treat "queued in the outbox" as sent; otherwise append the text on a new line to the chat's draft and post "Not sent" for that chat (no names when locked). Mark as read: the same call the chat screen makes, then remove that chat's delivered notifications. Reading a chat in the app, blocking it or deleting it removes its delivered notifications. Bound the background work to 20 seconds with a background task and end it on every path.
- [ ] **Step 4: Lock switched on.** A function `hideDeliveredContent()` replaces every delivered message notification with its locked form; A6 calls it when the lock is switched on.
- [ ] **Step 5: Badge.** The app badge is the number of chats with unread messages that are neither muted nor archived.

### Task A6: App lock

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Lock/AppLockPolicy.swift`, tests `AppLockPolicyTests.swift`
- Create: `apps/apple/App/Lock/{AppLock.swift,DeviceAuth.swift,LockCoverView.swift}`
- Modify: `AppCore` `UserSettings` (`appLock: Bool`, `lockAfterSeconds: Int`), `App/Features/Settings/PrivacyView.swift`, the root views (`App/Root/*`), `AppModel`
- Test: `apps/apple/AppUITests/iOS/AppLockTests.swift`

**Interfaces:**
- Produces: `AppLockPolicy.delays = [0, 60, 300, 3600]`, `validDelay(_:) -> Int`, `isLocked(enabled:leftAt:now:delay:expectedReturn:) -> Bool` on a monotonic clock (a clock reading earlier than `leftAt` locks; `expectedReturn` gives that one absence a delay of at least 300 seconds); `DeviceAuth` protocol (`available: Bool`, `authenticate(reason:) async -> AuthResult`) with `SystemDeviceAuth` (`LAContext`, policy `.deviceOwnerAuthentication`) and a sample fake; `AppLock` (`isLocked`, `unlock()`, `willResignActive()`, `didBecomeActive()`, `expectReturn()`); `appLockOn` for A5.
- Consumes: A5's `hideDeliveredContent()` behind one named hook (a no-op until A5 is merged).

- [ ] **Step 1: `AppLockPolicy` tests, then the rule**, mirroring `apps/android/core/.../lock/AppLockPolicy.kt` and its tests, including "Immediately", the 60-second fallback, the backwards clock, and the expected-return allowance not carrying over to a second absence.
- [ ] **Step 2: The gate.** While locked the root view shows only `LockCoverView` (the app name and an "Unlock" button labelled "Unlock [App]") and the in-call screen when a call is live; no chat view is in the hierarchy. The app starts locked when the setting is on. The system prompt is asked once per request, only while the scene is active; a cancelled prompt leaves the cover. When the scene becomes inactive with the setting on, a cover is shown at once so the app switcher snapshot shows nothing. A held notification tap opens its chat once, after unlock. The app's own pickers, camera and document viewers call `expectReturn()`. If the device has no passcode any more, the cover lets the person in.
- [ ] **Step 3: Privacy settings.** A "App lock" toggle with the subtitle from design section 7 (or "Set a screen lock in your phone's settings first." and disabled when unavailable), and a "Lock after" picker when on. Turning it on or off needs one passed prompt. A prompt answered after sign-out changes nothing. Sign-out resets both settings. Mac: the same, using Touch ID or the account password.
- [ ] **Step 4: UI test** with the sample fake: switch the lock on, background and reopen the app, see only the cover, unlock, see the chat list.

### Task A7: Profile name and coloured tiles

**Files:**
- Create: `apps/apple/Packages/AppCore/Sources/AppCore/Profile/{DisplayNameRule.swift,AvatarColor.swift}`, tests for both
- Modify: `App` `Avatar` view (required `colorKey: String?`), every call site, the theme's colour tokens
- Modify: the profile screen under `App/Features/Settings`, `AppModel`, `ChatStore` (`myStoredName: String`, `setMyDisplayName(_:)`), `Messaging` `LiveChatStore`
- Test: `apps/apple/AppUITests/iOS/ProfileNameTests.swift`

**Interfaces:**
- Produces: `DisplayNameRule.max = 40`, `clean(_:) -> String?`, `forDisplay(_:fallback:) -> String`; `AvatarColor.index(_ accountID: String) -> Int`; `AvatarTiles.light`, `.dark` (8 colours each, the values in Global Constraints).
- Consumes: the existing server call that updates the display name, and the existing "who am I" call that returns it.

- [ ] **Step 1: Rule tests, then the rules**, mirroring `apps/android/core/.../profile/DisplayNameRule.kt`, `AvatarColor.kt` and their tests, with the pinned hash values above and a contrast test that computes all 16 ratios against white (at least 4.5).
- [ ] **Step 2: Tiles.** `Avatar` takes the person's account ID as `colorKey` and draws a white letter on the tile; `nil` gives the neutral tile. Every call site passes the account ID, never the name.
- [ ] **Step 3: Own name.** `myStoredName` is the stored own name or `""` (the placeholder "You" is display only). When a session opens, the name is read from the server once and stored if none is stored; it never overwrites a stored name and does nothing after sign-out.
- [ ] **Step 4: Edit name.** Tapping the name row (with a trailing `pencil` symbol) opens "Edit name": a field pre-filled with the stored name, a "12/40" count, Save enabled only while `clean` accepts the text, Cancel. Save applies the name at once, calls the server, and on failure restores the stored name and shows "Couldn't save your name. Try again." Save, sign-out and delete account run one at a time; the editor closes when another of them starts.
- [ ] **Step 5: Names from other people** pass through `forDisplay` where a contact is built from stored or looked-up data.
- [ ] **Step 6: UI test** on sample data: open the profile, edit the name, see the new name on the profile and in Settings.
