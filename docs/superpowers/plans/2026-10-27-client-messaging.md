# Client Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan runs as a foundation stage and then four parallel lanes. Read "Parallel execution" first, then work only your lane's tasks.**

**Goal:** Replace `SampleChatStore` with real end-to-end encrypted messaging on iPhone and Mac: a local SQLite store, the message content format inside MLS, conversations and inbox sync, sending with an outbox and escrow copies, encrypted attachments, and a Notification Service Extension that decrypts pushes.

**Architecture:** There are two new Swift packages:
- `LocalStore`: a thin SQLite wrapper with its schema and a repository API.
- `Messaging`: the content format, conversation manager, inbox processor, outbox, attachments, notification processor and `LiveChatStore`.

`Messaging` uses `SecureMessaging` (plan 4) through `MLSEngine` and `ServerKit` (plan 6) through `ServerClient` with an injected `HTTPTransport`. Most logic runs in `swift test` on Linux against `FakeMLSEngine` and an in-memory `FakeServer`. CryptoKit, ImageIO, data protection and the NSE are tested in macOS CI.

**Tech Stack:** Swift 6 (strict concurrency), SwiftPM, system SQLite3 (FTS5 trigram), CryptoKit (HPKE and AES-GCM, Apple only), ImageIO and AVFoundation (Apple only), UserNotifications (NSE), Swift Testing, XcodeGen, and GitHub Actions (`ubuntu` + `swift:6.0`, and `macos-26`).

**Spec:** [GOAL.md](../../../GOAL.md) §4 Messaging, §5 and §8; [server design](../specs/2026-10-27-server-design.md) §7.2, §7.4 and §7.5 (written as "server §N"); [UI design](../specs/2026-10-27-app-ui-design.md); and the [roadmap contracts](README.md) (`SecureMessaging`, `ServerKit`, identifiers). The decisions below fill gaps those documents leave open, and they need owner confirmation.

## Design (decisions)

1. **One chat per contact.** `Chat.id` is the peer's server `accountID`. A chat can hold several server conversations (MLS groups), for example when both sides create a group at the same moment, or after one side reinstalls.
   - All of them stay receivable.
   - Messages are sent through the *preferred* conversation: the active one with the lexicographically smallest server conversation ID. Both sides therefore pick the same one without talking to each other.
2. **The content format is versioned JSON with short keys** (`ContentEnvelope`, below), carried as the MLS application-message plaintext.
   - JSON matches the server's wire format v1, is easy to write test vectors for, and is ready for a Kotlin client.
   - Revisit a binary format after the Phase 0 data measurements.
   - An unknown `k`, or `v > 1`, is stored and shown as "Update [App] to see this message". Unknown fields are ignored.
3. **The envelope `id` is the message ID and also the server `clientMsgID`.** It is a lowercase UUID, so it is the same on every device and server retries are idempotent.
4. **No typing indicators in Phase 1.** They would cost data for little benefit.
5. **Linked devices** get a one-time `LinkSnapshot` at linking time: contacts, chats (not messages), folders, blocked accounts and settings. Plan 6 encrypts it into the server link `sealed` blob.
   - History is not transferred.
   - After linking, own devices stay in step through the normal 1:1 groups. My own messages and receipts reach my other devices, because they are group members.
   - Folders and blocks made later are not synced in Phase 1.
6. **Receipts** are sent automatically: one `delivered` receipt per conversation after each inbox batch, and one `read` receipt when a chat is opened. They reach the sender's other devices too, which keeps read state in step.
7. **Attachments** use a fresh random 32-byte key per file, with chunked AES-256-GCM:
   - Chunk size: 65 536 bytes of plaintext, so each ciphertext chunk is 65 552 bytes.
   - Nonce: 4 zero bytes ‖ the 8-byte big-endian chunk index.
   - AAD: one byte, `0x01` for the last chunk and `0x00` otherwise, so a truncated file fails to decrypt.
   - Digest: SHA-256 over the whole ciphertext, carried in the envelope.
   - This is the STREAM construction and it allows Range-resumed downloads. Owner review: cryptography.
8. **Call history is local per device** (plan 8): `recordCall` writes a `calls` row and a `.call(summary)` message on this device only. No `call` envelope kind exists.
9. **Photo data saving:** photos are re-encoded to JPEG with a maximum side of 1 600 px and quality 0.7. The envelope carries an inline thumbnail (32 px maximum side, JPEG quality 0.4, at most 1 536 bytes) that is shown blurred until download. Voice messages are recorded as Opus in CAF, 16 kHz mono, at 16 kbps.
10. **Escrow copies** (server-readable conversations only) are sealed by `Escrow.seal(_:serverKey:)` in `Messaging` (plan 9 needs a single Swift owner of escrow sealing). It lives here, not in `SecureMessaging`, which only wraps the MLS binary; escrow is plain CryptoKit HPKE used only by the outbox. Sealing uses CryptoKit HPKE: Curve25519 / HKDF-SHA256 / ChaCha20-Poly1305, `info = "escrow-v1"`.
   - The output is `encapsulatedKey (32) ‖ ciphertext`, the framing Go's `hpke.Open` expects.
   - The plaintext is `{"conversationID","senderAccountID","sentAt","body":<ContentEnvelope object>}`.
   - A conversation's mode is taken **only from the server** (`GET /v1/conversations/{id}`, see cross-plan item 1), never from message content.
   - Attachment keys travel inside the envelope, so in server-readable chats the operator can read attachments too. The chat info text already says the admin can read the chat.
11. **Inbox pipeline: store raw first, then process.**
    - Fetched items go into `inbox_raw` and are acknowledged on the server only after that write is durable.
    - Each raw item is then processed, and the MLS result and its database rows are written in one transaction that deletes the raw row.
    - Every payload's SHA-256 goes into `processed`. The NSE and the app therefore never process the same MLS message twice: the push payload is the same bytes as the inbox item payload (server §7.2).
12. **Cross-process safety (app and NSE):**
    - SQLite in WAL mode with `busy_timeout = 5000`.
    - A `ProcessLock` (`flock` on `<appgroup>/messaging.lock`) is held only while MLS state and the database change together.
    - It is never held across a network call, and the app holds it only inside a `beginBackgroundTask` scope. This avoids iOS killing a suspended app that holds a shared-container lock (0xdead10cc).
13. **Search** uses an FTS5 `trigram` table over *folded* text:
    - `String.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)` is applied to both the text and the query.
    - Queries shorter than 3 characters fall back to `LIKE` on the folded column.
    - This works for Latin, Indic, CJK and RTL text without word breaking.
14. **`ChatStore` becomes `@MainActor`**, and `LiveChatStore` keeps a main-actor cache (chats and their latest 50 messages) so that the synchronous reads stay synchronous. Writes go to the database and then refresh the cache and call `onChange`.
15. **Data protection:**
16. **Call signals ride in the 1:1 conversation.** `LiveChatStore` implements plan 8's `CallSignalSealer` (`seal(_:to:)` and `open(_:from:)`):
    - `seal` encrypts a `callsig` envelope with `mls.encrypt` in the preferred conversation with that contact, creating the conversation if none exists.
    - `open` runs `mls.process` under the `ProcessLock`, then requires `.application` with a `callsig` body. It throws `CallSealError.wrongSender` unless the MLS sender's account (the part of `"<accountID>/<deviceID>"` before the slash) equals `contactID`, and `CallSealError.undecryptable` for every other failure.
    - The payload hash is marked processed. Because contact IDs equal server account IDs, no lookup is needed.
    - `CallSignalPayload`, `IceCandidate` and `CallSealError` live in **AppCore**, because both `Messaging` and plan 8's `CallCore` use them. `CallSignalSealer` stays in `CallCore`, and the app target adds `extension LiveChatStore: CallSignalSealer {}`, so `Messaging` never depends on `CallCore`.
17. **Echoed own commits** (plan 4 asks the server to echo accepted commits to the sender):
    - If the item's sender is my device and the stored epoch is already at least `item.epoch + 1`, the processor skips it (marks it processed and deletes the raw row).
    - Otherwise it passes the item to `mls.process`, which merges the pending commit. This covers a crash between the server's 200 and `commitAccepted`.
    - iOS: the database files (`.sqlite`, `-wal`, `-shm`) and the `ProcessLock` file use `.completeUntilFirstUnlock`, and the media directory uses `.complete`.
    - macOS: no file protection API; FileVault applies.

### Content envelope (exact JSON)

Every message is `{"v":1,"id":"<uuid>","at":<sent time, ms since 1970>,"k":"<kind>", …kind fields}`.

| `k` | Fields | Stored as |
|---|---|---|
| `text` | `t` (≤ 4 096 characters), `r?` (reply-to message ID) | `MessageContent.text` |
| `photo` | `a` (Att), `w`, `h`, `th?` (base64 JPEG), `r?` | `.photo(bytes:)` + `Message.media` |
| `voice` | `a`, `dur` (seconds, double), `r?` | `.voice(duration:)` + `media` |
| `file` | `a`, `name`, `mime`, `r?` | `.file(name:bytes:)` + `media` |
| `edit` | `target`, `t` | updates the target's text and sets `editedAt` (only if the sender matches) |
| `delete` | `target` | the target becomes `.deleted` (only if the sender matches) |
| `react` | `target`, `e` (one emoji; `""` removes) | one reaction per account per message |
| `receipt` | `s` (`"delivered"` or `"read"`), `ids` | updates the status of my messages; never shown |
| `callsig` | `p`: a `CallSignalPayload` JSON object (AppCore; plan 8 defines its fields) | **never stored**; returned to plan 8 through `CallSignalSealer.open` |

`Att = {"id":"<server attachment id>","key":"<base64 32 B>","dg":"<base64 SHA-256 of ciphertext>","sz":<plaintext bytes>,"csz":<ciphertext bytes>}`. Binary fields inside the envelope use standard base64, because the envelope is client-internal.

### Decisions I made — owner to confirm

1. One chat per contact, with a deterministic preferred conversation (Design 1).
2. JSON content envelope v1 (Design 2), with no typing indicators (Design 4).
3. Chunked AES-256-GCM (STREAM) for attachments (Design 7). Owner review: cryptography.
4. Photo re-encoding and the thumbnail and voice-recording settings (Design 9).
5. Escrow plaintext includes the full envelope, including attachment keys (Design 10). Owner review: cryptography.
6. No ongoing sync of folders or blocks between own devices; linking copies a one-time snapshot (Design 5).
7. Delivered and read receipts are always on in Phase 1, with no privacy toggle.
8. Reports include the last 10 messages of the chat, after the user confirms in a dialog.
9. Call payload types (`CallSignalPayload`, `IceCandidate`, `CallSealError`) live in AppCore and call signals are sealed in the 1:1 conversation (Design 16); call history stays on each device (Design 8).
10. `Escrow.seal(_:serverKey:)` lives in `Messaging`, not `SecureMessaging` (Design 10).

### Cross-plan changes this plan needs (also recorded in the roadmap at merge time)

1. **Server (plan 2, lane B):** add `GET /v1/conversations/{id}` → `{conversationID, mode, epoch}` for member devices (`not_found` otherwise). A receiver learns the mode from it after a Welcome.
2. **Server (plan 2, lane B):** add a Go test `TestEscrowSwiftVector` that opens `apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/escrow-vector.json`. This plan's B1 also checks the reverse direction.
3. **SecureMessaging (plan 4):**
   - Split out a product `SecureMessagingCore` (`MLSEngine`, the types, `FakeMLSEngine`) with no binary target, so `Messaging` builds and tests on Linux.
   - Add `func members(of group: GroupID) async throws -> [String]`.
   - The engine reads group state from storage on each call, or offers `func reload() async throws`, because the NSE changes that state from another process.
4. **ServerKit (plan 6):** must build on Linux, with Apple-only parts behind `#if canImport(AuthenticationServices)`. It must expose the server info document's `encryption.escrowKey`, and `LinkSnapshot` bytes in the linking flow.
5. **Calling (plan 8):**
   - `CallSignalPayload`, `IceCandidate` and `CallSealError` move to AppCore, with fields exactly as plan 8 F1 declares them. Whichever of plan 7 F2 or plan 8 F1 lands first adds them, and the other records a `Ruling:`.
   - `CallSignalSealer` stays in `CallCore`; `LiveChatStore` conforms through an extension in the app target.
   - `ChatStore.recordCall` is plan 8 F2's requirement. Plan 7 F2 adds it too if it is absent.
   - The NSE's `CALL` category branch goes in `applyCall(_:from:to:)` (D3).
6. **Server (plan 2):** echo accepted commits to the sending device (plan 4's request). Until then, Design 17's skip rule simply never fires.
7. **Roadmap:** add the package `apps/apple/Packages/Messaging` (this plan). Note that the Mac bundle ID in `project.yml` is `org.example.app.mac`, not `org.example.app`.

## Global Constraints

- iOS 17+, macOS 14+, Swift 6.0, and `SWIFT_STRICT_CONCURRENCY: complete` (matching `project.yml`).
- No new dependencies. SQLite is the system library, linked through a `systemLibrary` target. On Linux, CI installs `libsqlite3-dev`.
- Identifiers come from the roadmap: app group `group.org.example.app`, NSE bundle IDs `org.example.app.nse` (iOS) and `org.example.app.mac.nse` (Mac), keychain group `$(AppIdentifierPrefix)org.example.app.shared`.
- Never log message text, envelope JSON, keys, payloads, push data or display names.
- UI rules from AGENTS.md:
  - every new string goes in `Localizable.xcstrings`
  - no encryption labels outside chat info
  - never the words "MLS", "E2EE", "relay" or "codec" in the UI
  - VoiceOver labels on new controls
- Use injected clocks (`now: @Sendable () -> Date`), never `Date()`, in `Messaging`.
- Owner review before merge: `Messaging/Crypto/*` (escrow and attachment cipher), `ConversationManager`, `InboxProcessor`, `NotificationProcessor`, and the NSE targets.
- Commits: plain messages, no `Co-Authored-By`. Coding agents run on Sonnet 5.5.
- Package tests:
  - `cd apps/apple/Packages/LocalStore && swift test`
  - `cd apps/apple/Packages/Messaging && swift test`
  - App builds and UI tests run on `macos-26` CI. Never claim an app task passes without a green run.

## Review Focus

1. **The NSE decrypts a push, then the app syncs the same inbox item.** The second process must skip it by payload hash and still acknowledge it, with no "couldn't decrypt" placeholder. Pinned by D2 `TestDuplicateAfterNSE`.
2. **The app is suspended in the middle of a sync.** No lock may be held across `await` on the network. Pinned by A2 `TestLockNotHeldAcrossNetwork`, which uses a transport that blocks while the test asserts the lock is free.
3. **The app crashes after MLS processed a message but before the database write.** The raw row survives, reprocessing fails, and the user sees one "This message couldn't be decrypted" placeholder rather than nothing or a loop. Pinned by A2 `TestUndecryptablePlaceholder`.
4. **Both people start the chat at the same moment.** One chat results, both sides send through the same conversation, and messages from either group appear. Pinned by A1 `TestSimultaneousCreate`.
5. **A server-readable chat must include the escrow copy, and an end-to-end chat must never include one.** This holds whoever created the chat and even when the peer's content claims otherwise. Pinned by B1 `TestEscrowFollowsServerMode`.

---

## Parallel execution

```
Foundation (1 agent)   F1 → F2                         branch feat/client-messaging
Lanes (4 agents)       A1–A3 | B1–B3 | C1–C3 | D1–D3   worktrees: ../myApp-msg-<x>, branches msg/lane-<x>
Integration (1)        I1
```

This plan starts after plans 4 and 6 have merged, and after plan 1 is on `main`. Create `feat/client-messaging` from `main`.

The foundation fixes `LocalStore`'s schema and repository, the content envelope, the AppCore protocol changes, the ports, and `MessagingTestKit`. The lanes own disjoint directories:

| Lane | Owns |
|---|---|
| A Sync | `Messaging/Sources/Messaging/Sync/` |
| B Store and send | `Messaging/Sources/Messaging/Store/`, `Messaging/Sources/Messaging/Crypto/Escrow*.swift`, `App/AppModel.swift`, chat UI hooks |
| C Attachments | `Messaging/Sources/Messaging/Attachments/`, `Messaging/Sources/Messaging/Crypto/Attachment*.swift` |
| D Notifications | `Messaging/Sources/Messaging/Notifications/`, `apps/apple/NotificationService/`, the NSE targets in `project.yml`, entitlements |

Lanes B and D both edit `project.yml`: B adds the app group entitlement and D adds the targets. Resolve that at merge by keeping both. Merge in the order C, A, D, B, then do I1.

---

## Foundation

### Task F1: `LocalStore` package — SQLite wrapper, schema, repository

**Files:**
- Create: `apps/apple/Packages/LocalStore/Package.swift`
  - products: `LocalStore`
  - targets: `CSQLite` (`.systemLibrary`, `pkgConfig: "sqlite3"`, `providers: [.apt(["libsqlite3-dev"])]`, with a module map `link "sqlite3"` and header `shim.h` → `#include <sqlite3.h>`), `LocalStore`, and `LocalStoreTests`
- Create: `Sources/LocalStore/Database.swift`, `Migrations.swift`, `Schema.swift`, `Repository.swift`, `Records.swift`, `ProcessLock.swift`, `FileProtection.swift`, `Folding.swift`
- Test: `Tests/LocalStoreTests/DatabaseTests.swift`, `RepositoryTests.swift`, `SearchTests.swift`, `ProcessLockTests.swift`
- Modify: `.github/workflows/apple.yml`
  - `core` job: `apt-get update && apt-get install -y libsqlite3-dev`, then `swift test --no-parallel` in `Packages/LocalStore`
  - new `packages-macos` job on `macos-26`: `swift test` in `Packages/LocalStore` and `Packages/Messaging`, plus `xcodebuild -scheme LocalStore test -destination 'platform=iOS Simulator,name=iPhone 16'` in `Packages/LocalStore`

**Schema v1** (`Schema.swift`, migration 1; every table uses `STRICT`, and `PRAGMA foreign_keys=ON`, `journal_mode=WAL` and `busy_timeout=5000` are set on open):
```sql
contacts(account_id TEXT PRIMARY KEY, username TEXT NOT NULL, display_name TEXT NOT NULL, is_contact INTEGER NOT NULL, added_at INTEGER NOT NULL)
chats(id TEXT PRIMARY KEY, is_request INTEGER NOT NULL, unread INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, last_activity INTEGER NOT NULL)
conversations(server_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, group_id BLOB NOT NULL UNIQUE, mode TEXT NOT NULL CHECK(mode IN ('e2ee','server_readable')), epoch INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)
messages(id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, conversation_id TEXT, sender_account TEXT NOT NULL, sender_device TEXT, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, envelope BLOB NOT NULL, status TEXT NOT NULL, reply_to TEXT, edited_at INTEGER, deleted INTEGER NOT NULL DEFAULT 0, undecryptable INTEGER NOT NULL DEFAULT 0, folded TEXT NOT NULL DEFAULT '')
  -- index messages(chat_id, sent_at)
reactions(message_id TEXT NOT NULL REFERENCES messages ON DELETE CASCADE, account_id TEXT NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY(message_id, account_id))
message_fts USING fts5(folded, content='messages', content_rowid='rowid', tokenize='trigram')   -- plus insert/update/delete triggers
attachments(id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages ON DELETE CASCADE, kind TEXT NOT NULL, key BLOB NOT NULL, digest BLOB NOT NULL, size INTEGER NOT NULL, cipher_size INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('remote','downloading','local','failed')), downloaded INTEGER NOT NULL DEFAULT 0, local_path TEXT)
outbox(message_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, envelope BLOB NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL)
inbox_raw(cursor INTEGER PRIMARY KEY, kind TEXT NOT NULL, conversation_id TEXT NOT NULL, seq INTEGER, epoch INTEGER NOT NULL, sender_account TEXT NOT NULL, sender_device TEXT NOT NULL, payload BLOB NOT NULL, sent_at INTEGER NOT NULL)
processed(payload_hash BLOB PRIMARY KEY, processed_at INTEGER NOT NULL)
folders(id TEXT PRIMARY KEY, name TEXT NOT NULL, position INTEGER NOT NULL)
folder_chats(folder_id TEXT NOT NULL REFERENCES folders ON DELETE CASCADE, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, PRIMARY KEY(folder_id, chat_id))
blocked(account_id TEXT PRIMARY KEY, blocked_at INTEGER NOT NULL)
calls(id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats ON DELETE CASCADE, video INTEGER NOT NULL, outgoing INTEGER NOT NULL, outcome TEXT NOT NULL, started_at INTEGER NOT NULL, duration REAL NOT NULL)
kv(key TEXT PRIMARY KEY, value BLOB NOT NULL)
```

**Interfaces — Produces:**
- `public final class Database: @unchecked Sendable` (serialized by an internal `NSLock`):
  - `init(url: URL) throws`, `static func inMemory() throws -> Database`
  - `func execute(_ sql: String, _ args: [SQLValue] = []) throws`
  - `func query<T>(_ sql: String, _ args: [SQLValue] = [], row: (Row) throws -> T) throws -> [T]`
  - `func transaction<T>(_ body: (Database) throws -> T) throws -> T` (`BEGIN IMMEDIATE`)
- `public enum SQLValue { case null, int(Int64), double(Double), text(String), blob(Data) }`
- `public struct Row` with `int(_:)`, `double(_:)`, `text(_:)`, `blob(_:)` and `isNull(_:)`, all by column index
- `public func migrate(_ db: Database) throws`: uses `PRAGMA user_version`, and each migration runs in a transaction
- `public final class Repository: @unchecked Sendable`, `init(db: Database, now: @escaping @Sendable () -> Date)`, with typed methods on `Records.swift` structs (`ContactRecord`, `ChatRecord`, `ConversationRecord`, `MessageRecord`, `AttachmentRecord`, `OutboxRecord`, `RawInboxRecord`, `FolderRecord`, `CallRecord`), one-to-one with the tables:
  - contacts: `upsertContact`, `contact(id:)`, `contacts()`
  - chats: `upsertChat`, `chat(id:)`, `chats()` (ordered by `last_activity` desc), `setUnread`, `setRequest`, `deleteChat`
  - conversations: `upsertConversation`, `conversations(chatID:)`, `conversation(serverID:)`, `conversation(groupID:)`, `preferredConversation(chatID:)` (Design 1), `setEpoch`
  - messages: `insertMessage` (`INSERT OR IGNORE`; returns `Bool` inserted), `message(id:)`, `messages(chatID:before:limit:)` (newest page, returned oldest first), `updateStatus(ids:to:)` (never lowers a status: sending < sent < delivered < read), `applyEdit`, `markDeleted`, `setReaction`
  - search: `search(_ folded: String, limit: Int) -> [(chatID: String, messageID: String)]`
  - attachments: `upsertAttachment`, `attachment(id:)`, `attachment(messageID:)`, `setAttachmentState`
  - outbox: `enqueue`, `dueOutbox(at:)`, `bumpOutbox(id:nextAttempt:)`, `removeOutbox`
  - raw inbox: `insertRaw([RawInboxRecord])` (`INSERT OR IGNORE`), `rawItems(limit:)`, `deleteRaw(cursor:)`
  - processed: `markProcessed(hash:)`, `isProcessed(hash:)`, `pruneProcessed(olderThan:)`
  - folders: `folders()`, `addFolder`, `renameFolder`, `deleteFolder`, `moveFolder(id:to:)`, `setFolderChats`
  - blocks: `block`, `unblock`, `isBlocked`, `blockedIDs()`
  - calls: `insertCall`, `calls(limit:)`
  - settings: `get(_ key:)`, `set(_ key:, _ value:)`
  - `wipe()`
- `public func fold(_ s: String) -> String` (Design 13)
- `public final class ProcessLock: Sendable`, `init(url: URL)`, `func withLock<T>(timeout: Duration, _ body: () throws -> T) throws -> T`: polls `flock(LOCK_EX|LOCK_NB)` every 20 ms and throws `LockTimeout` when the time runs out
- `public enum StoreLocation { static func appGroup(_ id: String) -> URL? }` and `public func applyProtection(to url: URL, _ level: ProtectionLevel)`, where `ProtectionLevel` is `.untilFirstUnlock` or `.complete` (no-op outside iOS)

- [ ] **Step 1: Write the failing tests.**
  - `TestMigrateSetsVersion`: `user_version == 1`, and migrating twice is a no-op.
  - `TestTransactionRollsBack`: a throwing body leaves no row behind.
  - `TestInsertMessageIgnoresDuplicate`: the same ID twice returns `true` then `false`.
  - `TestStatusNeverLowers`: `read` followed by `delivered` stays `read`.
  - `TestMessagesPage`: of 120 messages, page 1 is the newest 50 in ascending order; `before:` the 71st gives the 50 before it.
  - `TestPreferredConversation`: active `"b…"` and `"a…"` → `"a…"`; an inactive `"0…"` is ignored.
  - `TestCascadeDeleteChat`: deleting a chat removes its messages, attachments, reactions, conversations and calls.
  - `TestSearchFoldsDiacritics`: `"Crème brûlée"` is found by `"creme"`; `"ＡＢＣ"` (full width) by `"abc"`; `"नमस्ते दोस्त"` by `"नमस्ते"`; `"東京タワー"` by `"京タ"`; `"שלום עולם"` by `"עולם"`; the 2-character `"çé"` is found through `LIKE`.
  - `TestSearchSkipsDeleted`: a deleted message no longer matches.
  - `TestProcessLockExclusive`: two `ProcessLock`s on the same file, where the second's `withLock(timeout: .milliseconds(100))` throws `LockTimeout` while the first holds the lock and succeeds after release.
  - `TestProtectionOnIOS` (`#if os(iOS)`): `applyProtection(.untilFirstUnlock)` → `FileManager` reports `.completeUntilFirstUnlock` for the database file.
- [ ] **Step 2:** `swift test` → FAIL (no such module).
- [ ] **Step 3: Implement.** Bind with `SQLITE_TRANSIENT`; prepared statements are finalized in `defer`.
- [ ] **Step 4:** `swift test` → PASS (on Linux, everything except the iOS-only test).
- [ ] **Step 5: Commit** `feat(localstore): SQLite store, schema v1, repository, search, process lock`.

### Task F2: Content format, AppCore changes, ports, `MessagingTestKit`

**Files:**
- Create: `apps/apple/Packages/Messaging/Package.swift`
  - products: `Messaging`, `MessagingTestKit`
  - dependencies, by path: `../AppCore`, `../LocalStore`, `../ServerKit`, `../SecureMessaging` (product `SecureMessagingCore`)
  - test resources: `Tests/MessagingTests/Resources`
- Create: `Sources/Messaging/Content/ContentEnvelope.swift`, `Sources/Messaging/Ports.swift`, `Sources/Messaging/Padding.swift`, `Sources/MessagingTestKit/FakeServer.swift`, `Sources/MessagingTestKit/FakeCiphers.swift`, `Sources/MessagingTestKit/TestDevice.swift`
- Create: `Tests/MessagingTests/Resources/content-v1.json` (test vectors)
- Modify (AppCore):
  - `Chats/Models.swift`: add `MessageContent.deleted` and `.unsupported`; add `Message.editedAt: Date?`, `Message.media: MediaState?` and `Message.undecryptable: Bool`, with defaults in `init`
  - `Chats/ChatStore.swift`: the protocol changes below, including `recordCall` (plan 8 F2's requirement; add it only if absent)
  - `Calls/CallSignalPayload.swift`: `CallSignalPayload`, `IceCandidate` and `CallSealError`, copied verbatim from plan 8 ([client-calling](2026-10-27-client-calling.md) "Call payload sealing" and F1). If plan 8 has already added them, import those and record a `Ruling:`
  - `Chats/SampleChatStore.swift`: implement the additions in memory
  - `Tests/AppCoreTests/*`: mark them `@MainActor` where they use the store
- Modify (UI): update the exhaustive switches in `App/Features/ChatList/ChatRowView.swift`, `App/Features/Chat/MessageBubbleView.swift` and `Message.previewText` for `.deleted` ("This message was deleted", shown in italic secondary style) and `.unsupported` ("Update [App] to see this message"). Add the new strings to `Localizable.xcstrings`.

**Interfaces — Produces:**
```swift
// AppCore
public struct MediaState: Hashable, Codable, Sendable { public var attachmentID: String; public var phase: Phase
    public enum Phase: Hashable, Codable, Sendable { case remote, downloading(Double), local(URL), failed } }
public struct MessageHit: Hashable, Sendable { public var chatID: String; public var messageID: String; public var preview: String }
@MainActor public protocol ChatStore: AnyObject {
    // …existing requirements unchanged, except:
    func addContact(username: String) async -> Contact?          // was synchronous
    var onChange: (@MainActor () -> Void)? { get set }
    func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message]
    func searchMessages(_ query: String, limit: Int) -> [MessageHit]
    func send(_ content: MessageContent, in chatID: String, replyTo: String?)   // existing send(_:in:) calls this with nil (protocol extension)
    func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String)
    func react(_ emoji: String, to messageID: String, in chatID: String)
    func edit(messageID: String, in chatID: String, text: String)
    func delete(messageID: String, in chatID: String)
    func download(messageID: String, in chatID: String)
    func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)   // plan 8 F2
}

// Messaging
public struct ContentEnvelope: Codable, Sendable, Equatable { public var v: Int; public var id: String; public var at: Int64; public var body: Body
    public enum Body: Sendable, Equatable { case text(String, replyTo: String?), photo(Att, w: Int, h: Int, thumb: Data?, replyTo: String?),
        voice(Att, duration: Double, replyTo: String?), file(Att, name: String, mime: String, replyTo: String?),
        edit(target: String, text: String), delete(target: String), react(target: String, emoji: String),
        receipt(ReceiptKind, ids: [String]), callSignal(CallSignalPayload), unsupported(kind: String) }
    public static func decode(_ data: Data) -> ContentEnvelope   // never throws: bad JSON → .unsupported("invalid")
    public func encoded() -> Data                                  // sorted keys, no whitespace
}
public struct Att: Codable, Sendable, Equatable { public var id: String; public var key: Data; public var dg: Data; public var sz: Int64; public var csz: Int64 }
public enum ReceiptKind: String, Codable, Sendable { case delivered, read }

public enum Padding { public static func unpad(_ b: Data) throws -> Data }   // server §7.4 format

public protocol EscrowSealer: Sendable { func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data }
public enum Escrow { public static func seal(_ plaintext: Data, serverKey: Data) throws -> Data }   // Apple only (CryptoKit); B1 implements it; plan 9's owner of escrow sealing
public protocol AttachmentCipher: Sendable {
    func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64)
    func decrypt(file: URL, to: URL, key: Data, digest: Data) throws
}
public protocol ConversationManaging: Sendable {                 // lane A implements; lane B consumes
    func preferredConversation(with peer: String) async throws -> ConversationRecord   // creates one if needed
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws
}
public struct MessagingDeps: Sendable {
    public var me: (accountID: String, deviceID: String)
    public var server: ServerClient; public var mls: MLSEngine; public var repo: Repository
    public var lock: ProcessLock; public var escrow: EscrowSealer; public var attachments: AttachmentCipher
    public var mediaDirectory: URL; public var now: @Sendable () -> Date
}

// MessagingTestKit
public actor FakeServer   // implements server §7.2 + users, blocks, reports, attachments (with Range) + GET /v1/conversations/{id}
public func transport(for device: TestDevice) -> HTTPTransport   // bearer "test.<acct>.<dev>"
public func events(for deviceID: String) -> AsyncStream<ServerEvent>
public struct TestDevice { public let accountID, deviceID: String; public let deps: MessagingDeps }   // FakeMLSEngine, in-memory DB, temp dirs
public func makeDevice(_ server: FakeServer, account: String, username: String) async -> TestDevice
public struct FakeEscrowSealer: EscrowSealer   // returns "ESCROW:" + plaintext
public struct FakeAttachmentCipher: AttachmentCipher   // XOR with key[0]; digest = SHA-256 via a tiny pure-Swift SHA-256 in the test kit
```

- [ ] **Step 1: Write the failing tests.**
  - `TestEnvelopeVectors`: each case in `content-v1.json` (one per kind, including `callsig`) decodes to the expected `Body`, and `encoded()` gives back the exact vector bytes.
  - `TestEnvelopeForwardCompat`:
    - `{"v":1,"id":"x","at":1,"k":"poll"}` → `.unsupported("poll")`
    - `"v":2` → `.unsupported`
    - garbage → `.unsupported("invalid")`
    - an extra unknown field is ignored
  - `TestPaddingMatchesServer`: `unpad` of the server §7.4 layout for lengths 0, 254 and 1000 gives the original bytes back; a declared length beyond the buffer throws.
  - `TestFakeServerConversation`: two `TestDevice`s → claim, create, commit at epoch 0 → 200; a second commit at epoch 0 → `ServerError(status:409, code:"stale_epoch")`.
  - In AppCore, `SampleChatStoreTests` gets new tests: `searchMessages("lunch")` finds the sample message; `delete` → the content is `.deleted`; `react` toggles.
  - `TestCallSignalEnvelope`: a `callsig` envelope whose `p` has every `CallSignalPayload` field set round-trips byte for byte; the vector is in `content-v1.json`.
- [ ] **Step 2:** `swift test` in Messaging and AppCore → FAIL.
- [ ] **Step 3: Implement.** Write `content-v1.json` by hand from the envelope table above. The test kit's SHA-256 exists only because CryptoKit is unavailable on Linux.
- [ ] **Step 4:** Both packages' `swift test` → PASS. Push and check that the macOS UI tests (`-sampleData`) stay green: `gh run watch`.
- [ ] **Step 5: Commit** `feat(messaging): content envelope v1, ChatStore changes, ports, test kit`. The lanes can start now.

---

## Lane A — Sync (`Sources/Messaging/Sync/`)

Owner review: all of it. Test command: `swift test --filter Sync` in Messaging.

### Task A1: Conversation manager

**Files:** Create `Sync/ConversationManager.swift`. Test: `Tests/MessagingTests/Sync/ConversationManagerTests.swift`.

**Interfaces — Produces:** `public actor ConversationManager: ConversationManaging`, `init(deps: MessagingDeps)`, plus:
- `func create(with peer: String) async throws -> ConversationRecord`:
  1. claim KeyPackages for the peer and for my own account, dropping my current device
  2. `POST /v1/conversations`, then `mls.createGroup()` and `mls.add`
  3. commit at epoch 0 with a welcome and `addDevices` = all claimed devices
  4. `commitAccepted`, then store the conversation with its mode from the create response
- `func joined(group: GroupID, serverConversationID: String) async throws`: called by the inbox processor; reads the mode with `GET /v1/conversations/{id}`, creates the chat as a request when the peer is not a contact, and stores the conversation
- `handleStaleEpoch`: `commitRejected`, then `InboxSync.syncOnce()` through an injected closure, then retry, at most 3 times, then throw `SyncError.epochConflict`

- [ ] **Step 1: Failing tests** (using `FakeServer` with devices ana1, ana2, bob1 and bob2).
  - `TestCreateAddsAllDevices`: ana1 creates with bob → the server shows 4 member devices; bob1, bob2 and ana2 each have a welcome item.
  - `TestCreateUsesServerMode`: the FakeServer policy is `server_readable` → the stored `mode` is `server_readable`.
  - `TestStaleEpochRetries`: the FakeServer rejects ana1's commit once with `stale_epoch` → ana1 syncs, retries, and succeeds at the next epoch.
  - `TestStaleEpochGivesUp`: rejected 4 times → `SyncError.epochConflict`.
  - `TestSimultaneousCreate`: ana1 and bob1 create at the same moment → 2 server conversations; both devices' `preferredConversation(with:)` returns the same server ID (the smaller one), and the chat count on each device is 1.
  - `TestJoinedFromStrangerIsRequest`: carol (not a contact) creates → bob's chat has `is_request = 1`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): conversation manager with epoch-conflict retry`.

### Task A2: Inbox processor

**Files:** Create `Sync/InboxProcessor.swift`, `Sync/IncomingHandler.swift`. Test: `InboxProcessorTests.swift`.

**Interfaces — Produces:**
- `public actor InboxProcessor`, `init(deps:conversations:handler:)`
- `func syncOnce(maxItems: Int = .max) async throws -> Int`:
  1. fetch with `GET /v1/inbox?after=<kv inbox.cursor>&limit=100` until `more` is false
  2. `insertRaw`, then ack `upTo`, then store the cursor
  3. `drainRaw()`
- `func run(events: AsyncStream<ServerEvent>) async`: an `.inbox` event inserts the raw item and then drains; it also calls `syncOnce()` on start
- `func drainRaw() async`: for each raw item in cursor order:
  - if `hash(payload)` is already processed → delete the raw row only
  - otherwise, inside `lock.withLock(timeout: 5 s)`, run `mls.process(payload)`, then `IncomingHandler.apply(_:raw:)`, `markProcessed` and `deleteRaw`, all in one `repo` transaction
  - an MLS error → insert a placeholder message (`undecryptable = 1`, ID `"undecryptable-<cursor>"`), mark the hash processed, and delete the raw row
- `public struct IncomingHandler` (shared with D2): `func apply(_ incoming: Incoming, raw: RawInboxRecord, db: Repository) throws -> AppliedResult`:
  - `.joined` → `conversations.joined`
  - `.commit(removedMe: true)` → mark the conversation inactive; any other `.commit` → store the epoch
  - an echoed own commit follows Design 17
  - `.application` → decode the envelope and apply it per the content table: edit and delete check the sender; receipts update statuses; a `callsig` returns `.callSignal`; everything else returns `.stored(MessageRecord)` or `.ignored`
- Receipts: after each drain, send one `delivered` receipt per conversation listing the new message IDs, through lane B's `Outbox.sendControl` (a protocol from F2: `ControlSender`).

- [ ] **Step 1: Failing tests.**
  - `TestSyncStoresAndAcks`: bob1 receives 3 messages → they are stored in order, the server inbox for bob1 is empty, and `kv inbox.cursor` equals the last cursor.
  - `TestSyncPagesPast100`: 250 items → all stored.
  - `TestDuplicatePayloadSkipped`: pre-mark a payload's hash → it is not stored again, yet it is still acked.
  - `TestUndecryptablePlaceholder`: `FakeMLSEngine` is set to fail on one payload → one placeholder with `undecryptable == true`, and the next items are still processed. Running `drainRaw` again adds nothing.
  - `TestEditDeleteOnlyBySender`: an `edit` for ana's message sent by carol is ignored, and one sent by ana2 is applied.
  - `TestRemovedMeDeactivates`: a commit removing bob2 → bob2's conversation is inactive.
  - `TestLockNotHeldAcrossNetwork`: the transport blocks on `GET /v1/inbox`; while it blocks, `lock.withLock(timeout: .milliseconds(50))` succeeds from the test.
  - `TestCallSignalNotStored`: a `callsig` envelope in the inbox → `AppliedResult.callSignal(payload, sender)`, no message row, and the processed hash marked. Call signals normally arrive over `call.*` frames; an inbox copy is ignored.
  - `TestOwnCommitEchoSkipped`: the FakeServer echoes ana1's accepted commit back to ana1 → no `mls.process` call, and the raw row is deleted.
  - `TestOwnCommitEchoAfterCrash`: the stored epoch is still at the pre-commit value → the echo goes to `mls.process`, and the stored epoch advances.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): inbox sync — raw-first, dedupe, ordered MLS processing`.

### Task A3: Device changes and KeyPackages

**Files:** Create `Sync/DeviceSync.swift`. Test: `DeviceSyncTests.swift`.

**Interfaces — Produces:** `public actor DeviceSync`, `init(deps:conversations:)`, with:
- `func reconcileOwnDevices() async throws`: `GET /v1/devices`; for each active conversation, add any own device not in `mls.members(of:)` (claiming my own account's KeyPackages), and commit using the stale-epoch path
- `func handleRevoked(deviceID: String) async throws`: remove `"<me>/<deviceID>"` from every group containing it; if `deviceID` is mine, call `wipeLocalData()`
- `func replenishKeyPackages() async throws`: runs at most every 6 h (kv `kp.checkedAt`); when the count is below 10, upload 50
- `func wipeLocalData() async throws`: `repo.wipe()`, delete the media directory and the MLS storage
- `func exportSnapshot() throws -> Data` and `func importSnapshot(_ data: Data) throws`: `LinkSnapshot {v:1, contacts, chats:[{id,isRequest,muted}], folders, blocked, settings: [String: Data]}` as JSON

- [ ] **Step 1: Failing tests.**
  - `TestNewOwnDeviceAdded`: ana links ana3 (FakeServer adds the device) → `reconcileOwnDevices()` on ana1 adds ana3 to both of ana's conversations; ana3 receives the welcomes; ana2 running reconcile at the same time ends with one add (the other side sees the member already present).
  - `TestRevokedRemoved`: `handleRevoked(ana2)` on ana1 → the server's member list for each conversation drops ana2.
  - `TestRevokedSelfWipes`: `handleRevoked(<own deviceID>)` → `repo.chats()` is empty and the media directory is gone.
  - `TestReplenish`: count 4 → 50 uploaded; a second call within 6 h makes no request; at count 12 nothing is uploaded.
  - `TestSnapshotRoundTrip`: export from ana1 and import into a fresh device → contacts, chats, folders and blocks are equal, and there are no messages.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): own-device reconciliation, revocation, key package refill, link snapshot`.

---

## Lane B — Store and send

Test command: `swift test --filter Store` in Messaging, plus macOS CI for `EscrowSealerTests`.

### Task B1: Outbox and escrow

**Files:** Create `Store/Outbox.swift`, `Crypto/Escrow.swift` and `Crypto/EscrowSealer.swift` (CryptoKit, `#if canImport(CryptoKit)`), and `tools/vectors/escrow/main.go` (writes the Go-made vector). Test: `OutboxTests.swift`, `EscrowSealerTests.swift` (macOS only), `Resources/escrow-vector.json`.

**Interfaces — Produces:**
- `public actor Outbox: ControlSender`, `init(deps:conversations:)`
- `func enqueue(_ envelope: ContentEnvelope, chatID: String) throws`: stores the message as `sending` and adds an outbox row in one transaction
- `func flush() async`: for each due item:
  1. `preferredConversation`
  2. `mls.encrypt`
  3. if the mode is `server_readable`, `escrow.seal(plaintext JSON per Design 10, to: serverInfo.escrowKey)`
  4. `POST /v1/conversations/{id}/messages {epoch, ciphertext, escrow?, clientMsgID: envelope.id}`
  5. 200 → status `sent` and remove the row; `stale_epoch` → `handleStaleEpoch`, then retry immediately once
  6. a network error → back off 2^attempts s (capped at 300 s)
  7. `escrow_required` or `escrow_forbidden` → refresh the mode with `GET /v1/conversations/{id}` and retry once
- `func sendControl(_ body: ContentEnvelope.Body, chatID: String) async`: not stored, sent immediately
- `public enum Escrow`, with `static func seal(_ plaintext: Data, serverKey: Data) throws -> Data`, in `Crypto/Escrow.swift` (Design 10)
- `public struct CryptoKitEscrowSealer: EscrowSealer`, which calls `Escrow.seal`

- [ ] **Step 1: Failing tests.**
  - `TestSendReachesPeer`: ana1 enqueues a text, then `flush` → bob1 syncs and has the message; ana1's status is `sent`.
  - `TestRetryWithBackoff`: the transport fails twice, with the clock advanced between attempts → the third attempt succeeds; `attempts` was 2.
  - `TestSameClientMsgIDOnRetry`: the server gets the same `clientMsgID` on every retry, and bob1 stores one message.
  - `TestEscrowFollowsServerMode`:
    - a server-readable conversation → the request carries `escrow` beginning with `ESCROW:`, and its JSON `body` equals the envelope
    - an e2ee conversation → no `escrow` key
    - a peer envelope can't change this, because the mode comes only from the stored conversation, which the server sets
  - `TestEscrowSwiftOpensGoVector` (macOS): open the vector with CryptoKit `HPKE.Recipient` → the plaintext matches.
  - `TestEscrowRoundTrip` (macOS): seal, then open with a test recipient → equal; the output length is 32 + plaintext + 16.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.** Generate the vector with `go run ./tools/vectors/escrow > apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/escrow-vector.json`. It uses `crypto/hpke` with a fixed X25519 key, and the output holds the private key bytes, the plaintext and the sealed bytes.
- [ ] **Step 4:** Run → PASS on Linux. Push, and macOS CI runs the CryptoKit tests green.
- [ ] **Step 5: Commit** `feat(messaging): outbox with retries and escrow copies`.

### Task B2: `LiveChatStore`

**Files:** Create `Store/LiveChatStore.swift`, `Store/ChatMapping.swift`. Test: `LiveChatStoreTests.swift` (`@MainActor`).

**Interfaces — Produces:** `@MainActor public final class LiveChatStore: ChatStore`, `init(deps: MessagingDeps, outbox: Outbox, inbox: InboxProcessor, conversations: ConversationManaging, attachments: AttachmentService?)`, implementing every `ChatStore` requirement. The cache is reloaded after each write or inbox drain, and then `onChange` is called.

| Requirement | Behavior |
|---|---|
| `send` and `reply` | `Outbox.enqueue` with a fresh lowercase UUID, then `flush` in a `Task` |
| `addContact` | `GET /v1/users/{username}` → upsert the contact with `is_contact = 1` |
| `accept(requestID:)` | the request becomes a normal chat and the sender a contact |
| `block` | local `block`, then `PUT /v1/blocks/{id}`; the chat is removed from the lists |
| `report` | `POST /v1/reports` with the last 10 messages (`sentAt`, preview text) and reason `abuse` |
| `markRead` | unread is set to 0, and `sendControl(.receipt(.read, ids))` covers the unread messages from the peer |
| `incomingCall` | unchanged rule: a contact → `.ring`; otherwise a missed-call message in the request, then `.silentMissed` |
| `messages` and `searchMessages` | from `Repository`, with the query folded first |
| folders | through `Repository` |
| `react`, `edit`, `delete` | send a control envelope and apply it locally at once (edit and delete only for my own messages) |
| `sendAttachment` and `download` | forwarded to `AttachmentService` (lane C) |

`ChatMapping`: `MessageRecord` + `AttachmentRecord` → AppCore `Message`. `Message.status` comes from the record. `Chat.encryption` is `.serverReadable` when the preferred conversation's mode is `server_readable`.

- [ ] **Step 1: Failing tests.**
  - `TestChatsListedByActivity`.
  - `TestSendShowsSendingThenSent`: right after `send`, the last message's status is `.sending`; after the flush it is `.sent`; once bob syncs and sends a delivered receipt, ana's status is `.delivered`; after bob's `markRead`, it is `.read`.
  - `TestRequestAcceptFlow`.
  - `TestBlockHidesAndCallsServer`: the FakeServer records `PUT /v1/blocks/<bob>`.
  - `TestReportSendsLastTen`: of 12 messages, the request has 10.
  - `TestEditOnlyOwn`: editing the peer's message is a no-op.
  - `TestOnChangeCalledAfterInbox`.
  - `TestEncryptionFlagFromMode`: a server-readable conversation → `chat.encryption == .serverReadable`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): LiveChatStore`.

### Task B3: Call signal sealing, call history, app wiring

**Files:**
- Create: `Store/LiveChatStore+Calls.swift`
- Create: `apps/apple/App/Calls/LiveChatStore+CallSignalSealer.swift`, containing `extension LiveChatStore: CallSignalSealer {}`. It is the only file that imports `CallCore`. If plan 8 hasn't merged yet, skip this file and record a `Ruling:`.
- Modify:
  - `apps/apple/App/AppModel.swift`: `chatStore: any ChatStore`; `-sampleData` → `SampleChatStore`; otherwise build the `LiveChatStore` graph from plan 6's session (`SessionStore`, `ServerClient`, `LiveConnection`) and plan 4's `makeMLSEngine(identity:storage:)`
  - `AppModel`: `chats(in:matching:)` uses `searchMessages` for message matches
  - `ChatView`: the report confirmation dialog
  - `project.yml`: add the app group entitlement to both apps
  - `App/Resources/Localizable.xcstrings`
- Test: `CallSignalSealingTests.swift`

**Interfaces — Produces** (Design 16; the signatures match plan 8's `CallSignalSealer`):

```swift
extension LiveChatStore {
    public func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data
    public func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload
    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)   // ChatStore requirement: a calls row + a local .call message, then onChange
}
```

The report dialog's copy: title "Report this chat?" and message "The last 10 messages will be sent to the server admin.", with the buttons "Report" (destructive) and "Cancel".

- [ ] **Step 1: Failing tests.**
  - `TestCallSignalRoundTrip`: ana seals `CallSignalPayload(kind: .offer, callID: "c1", sdp: "v=0…", dtlsFingerprint: "sha-256 AB:CD")` to bob → bob's `open(_, from: ana)` returns an equal payload.
  - `TestOpenWrongSender`: carol seals to bob → `open(_, from: ana)` throws `CallSealError.wrongSender`.
  - `TestOpenGarbage`: random bytes → `CallSealError.undecryptable`.
  - `TestOpenNonCallContent`: a sealed text message → `CallSealError.undecryptable`.
  - `TestSealCreatesConversation`: with no conversation yet, `seal` creates one, and bob can open it after syncing the welcome.
  - `TestRecordCallLocalOnly`: `recordCall(.audio(duration: 125), contactID: bob, outgoing: true, at: d)` → ana's chat ends with `.call(.audio(duration: 125))` sent by `me` at `d`, plus one `calls` row; the FakeServer received no message.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS. Push, and check that the iOS and macOS UI tests are green with `-sampleData`.
- [ ] **Step 5: Commit** `feat(messaging): call signal sealing, local call history, live store wiring`.

---

## Lane C — Attachments

Owner review: `Crypto/AttachmentCipher.swift`. Test commands: `swift test --filter Attachments` on Linux, and macOS CI for the CryptoKit, ImageIO and AVFoundation tests.

### Task C1: Chunked attachment cipher

**Files:** Create `Crypto/AttachmentCipher.swift` (`CryptoKitAttachmentCipher`). Test: `AttachmentCipherTests.swift` (macOS).

- [ ] **Step 1: Failing tests.**
  - `TestRoundTripSizes`: plaintext sizes 0, 1, 65 535, 65 536, 65 537 and 200 000 → cipher sizes 16, 17, 65 551, 65 552, 65 569 and 200 064 (each chunk adds 16; an empty file is one empty last chunk), and decrypting gives back equal bytes.
  - `TestTruncationDetected`: dropping the last chunk of a 3-chunk file → decrypt throws.
  - `TestChunkSwapDetected`: swapping chunks 0 and 1 → throws.
  - `TestDigestChecked`: one flipped bit anywhere → throws `AttachmentError.digest` before any plaintext is written.
  - `TestStreamsWithoutLoadingFile`: a 50 MB file → peak resident memory grows by less than 4 MB (measured with `mach_task_basic_info`).
- [ ] **Step 2:** Run on macOS (`swift test --filter AttachmentCipher`) → FAIL.
- [ ] **Step 3: Implement** Design 7 with `FileHandle` reads of 65 536 bytes.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): chunked AES-GCM attachment cipher`.

### Task C2: Upload, download, auto-download

**Files:** Create `Attachments/AttachmentService.swift`. Test: `AttachmentServiceTests.swift` (Linux, using `FakeAttachmentCipher`).

**Interfaces — Produces:** `public actor AttachmentService`, `init(deps:outbox:network: @escaping @Sendable () -> NetworkType, settings: @escaping @Sendable () -> AutoDownloadSettings)`, with:
- `func send(file: URL, kind: MediaKind, chatID: String, replyTo: String?) async throws`: encrypt into the media directory → `POST /v1/attachments` → build the `Att` → `Outbox.enqueue` the photo, voice or file envelope
- `func download(attachmentID: String) async throws`: resumes from `downloaded` using `Range: bytes=<downloaded>-`; when complete, verifies and decrypts to `media/<id>.<ext>` with `.complete` protection, and sets the state to `local`; on failure the state is `failed`
- `func autoDownload(for message: MessageRecord) async`: uses `AutoDownloadPolicy.shouldDownload`
- Progress comes from `onChange` throttling, about 4 times a second.

- [ ] **Step 1: Failing tests.**
  - `TestSendUploadsThenEnqueues`: the FakeServer has the blob; bob's message has `media.phase == .remote` with the right `sz`.
  - `TestDownloadResumes`: the transport drops the connection after 70 000 bytes → the second call sends `Range: bytes=70000-` and finishes; the state is `local`; the bytes equal the original.
  - `TestDigestFailureMarksFailed`.
  - `TestAutoDownloadPolicy`: on Wi-Fi with `.all`, a photo downloads; on mobile with `.none`, a photo doesn't but voice does (`voiceAlways`).
  - `TestExpiredAttachment404`: the server says 404 → the state is `failed`, and there is no retry loop.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): attachment upload, resumable download, auto-download`.

### Task C3: Photo preparation and voice notes

**Files:** Create `Attachments/MediaPreparation.swift` (ImageIO, `#if canImport(ImageIO)`), `Attachments/VoiceRecorder.swift` (AVFoundation). Modify the composer in `App/Features/Chat/ChatView.swift`: the attachment button uses `PhotosPicker` and `fileImporter` → `model.sendAttachment`, and the mic records with `VoiceRecorder`, with the VoiceOver labels "Attach" and "Record voice message". Test: `MediaPreparationTests.swift` (macOS).

**Interfaces — Produces:**
- `public enum MediaPreparation { static func preparePhoto(_ url: URL, to: URL) throws -> (w: Int, h: Int, thumb: Data) }` (Design 9)
- `public final class VoiceRecorder` with `start()`, `stop() -> (URL, Double)` and `cancel()`. It records Opus in CAF (`kAudioFormatOpus`, 16 000 Hz, 1 channel, `AVEncoderBitRateKey 16000`).

- [ ] **Step 1: Failing tests.**
  - `TestPhotoResized`: a 4 000×3 000 PNG → a JPEG of 1 600×1 200.
  - `TestThumbnailSmall`: the thumbnail is at most 1 536 bytes and its longest side is 32.
  - `TestExifStripped`: the output has no GPS dictionary.
  - `TestVoiceSettings`: `VoiceRecorder.settings[AVFormatIDKey] == kAudioFormatOpus` and the bit rate is 16 000.
- [ ] **Step 2:** Run on macOS → FAIL.
- [ ] **Step 3: Implement.** Use `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceThumbnailMaxPixelSize`. Write with `CGImageDestination` without metadata. If 0.4 quality still exceeds 1 536 bytes, lower it in 0.1 steps.
- [ ] **Step 4:** Run → PASS. Push, and check that the UI tests are green.
- [ ] **Step 5: Commit** `feat(messaging): photo re-encoding with thumbnails, Opus voice notes`.

---

## Lane D — Notifications

Owner review: all of it. Test commands: `swift test --filter Notifications` on Linux, and the NSE build and unit test on macOS CI.

### Task D1: NSE targets, app group, entitlements

**Files:**
- Create: `apps/apple/NotificationService/NotificationService.swift`, `NotificationService/Info.plist` keys (via `project.yml` `INFOPLIST_KEY_*`), `NotificationService/NSE-iOS.entitlements`, `NotificationService/NSE-Mac.entitlements`, `App/AppiOS.entitlements` and `App/AppMac.entitlements` additions
- Modify: `apps/apple/project.yml`
  - targets `AppNSEiOS` (`app-extension`, iOS, bundle ID `org.example.app.nse`) and `AppNSEMac` (`app-extension`, macOS, bundle ID `org.example.app.mac.nse`), both with sources `NotificationService` and dependencies on the Messaging, LocalStore, ServerKit and SecureMessaging packages
  - embed each NSE in its app
  - entitlements: `com.apple.security.application-groups: [group.org.example.app]` and `keychain-access-groups`; Mac adds `com.apple.security.app-sandbox` and `com.apple.security.network.client`
  - the `com.apple.developer.usernotifications.filtering` entitlement only when the build setting `NSE_FILTERING=YES` (default `NO`, until Apple grants it)

- [ ] **Step 1:** Add a check in `scripts/test-ios.sh` and `scripts/test-macos.sh`: after the build, `plutil -extract NSExtension.NSExtensionPointIdentifier raw` on the built NSE's `Info.plist` must print `com.apple.usernotifications.service`. Push → FAIL (no target).
- [ ] **Step 2:** Implement the targets, with a stub `NotificationService` that delivers `bestAttemptContent` unchanged.
- [ ] **Step 3:** Push → CI green on both platforms.
- [ ] **Step 4: Commit** `feat(nse): notification service extension targets, app group, entitlements`.

### Task D2: Notification processor (shared logic)

**Files:** Create `Notifications/NotificationProcessor.swift`. Test: `NotificationProcessorTests.swift` (Linux).

**Interfaces — Produces:**
```swift
public enum NotificationOutcome: Equatable, Sendable {
    case show(title: String, body: String, threadID: String)
    case drop                                   // only honored when NSE_FILTERING is on; otherwise shown as `fallback`
    case fallback                               // "[App]" / "New message"
    case incomingCall(CallSignalPayload, from: Contact)   // plan 8's CALL branch decides what to show
}
public struct NotificationProcessor: Sendable {
    public init(deps: MessagingDeps, handler: IncomingHandler, conversations: ConversationManaging)
    public func process(pushData: Data, deadline: Date) async -> NotificationOutcome
}
```

The algorithm:
1. Unpad. An empty payload → run `InboxProcessor.syncOnce(maxItems: 20)`, then show the newest stored message (or `fallback`).
2. A processed hash → `drop`.
3. Take the lock (2 s timeout, otherwise `fallback`), then `mls.process`, `IncomingHandler.apply`, `markProcessed`.
4. Decide what to show:
   - a stored message from a contact → `show(displayName, preview, chatID)`
   - from a non-contact → `show("Message request", "Open [App] to see it", chatID)`
   - a muted or blocked chat → `drop`
   - a receipt, edit or delete → `drop`
   - a reaction → `show(displayName, "Reacted \(emoji)", chatID)`
   - a `callsig` → `incomingCall(payload, from: <contact or a stranger placeholder>)`
5. Past the `deadline`, or on any error → `fallback`.

All of these strings go in the String Catalog of the NSE targets, using a shared `.xcstrings` file in `NotificationService/`.

- [ ] **Step 1: Failing tests.**
  - `TestShowsSenderAndPreview`: ana sends "Lunch?" → bob's processor run on ana's push payload gives `show("Ana", "Lunch?", <ana accountID>)`, and the message is stored.
  - `TestDuplicateAfterNSE`: run the processor, then bob's `InboxProcessor.syncOnce()` → one stored message and no placeholder; the server inbox is acked.
  - `TestStrangerShowsRequest`.
  - `TestMutedDrops` and `TestReceiptDrops`.
  - `TestLockBusyFallsBack`: the test holds the lock → `fallback` within 2.5 s.
  - `TestEmptyPayloadSyncs`: an empty padded payload → the inbox is fetched and `show` uses the newest message.
  - `TestDeadlinePassed`: `deadline` is in the past → `fallback`.
  - `TestCallSignalOutcome`: a `callsig` payload → `.incomingCall`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(messaging): notification processor`.

### Task D3: NSE glue and on-device memory budget

**Files:**
- Modify: `NotificationService/NotificationService.swift`:
  - build `MessagingDeps` from the app group: database, `ProcessLock`, MLS storage, and `SessionStore` from the shared keychain group
  - read `userInfo["d"]` (base64), call `process(pushData:deadline: now + 25 s)`, and map the outcome to `bestAttemptContent` (`title`, `body`, `threadIdentifier`, `sound`)
  - with `NSE_FILTERING`, `drop` delivers an empty `UNNotificationContent()`
  - `serviceExtensionTimeWillExpire` delivers `fallback`
  - **The extension point for plan 8:** `.incomingCall` goes to `func applyCall(_ payload: CallSignalPayload, from contact: Contact, to content: UNMutableNotificationContent)`, in its own file `NotificationService/NotificationService+Call.swift`. Plan 7's body sets only the title (the contact's name) and the body "Incoming call". Plan 8 lane K replaces that file's body (the `CALL` category, `.timeSensitive`, ring age) and nothing else.
- Create: `apps/apple/AppUITests/iOS/NotificationServiceTests.swift`, which uses `xcrun simctl push` with a payload built by a test helper from a `FakeMLSEngine` message, under the `-uiTesting -sampleData -fakeMLS` launch arguments; plans 4 and 6 provide a fake engine switch

- [ ] **Step 1: Failing test.** `testPushShowsDecryptedPreview`: a simulator push for "Lunch?" from Ana → the notification banner reads `Ana` / `Lunch?`, checked through `XCUIApplication(bundleIdentifier: "com.apple.springboard")`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3: Implement.** Mark the memory budget in code: open the database lazily, keep page caches small (`PRAGMA cache_size=-2000`), and process exactly one push.
- [ ] **Step 4:** Push → CI green. Record on-device memory as a manual check for the owner: the NSE stays under 24 MB in Xcode's memory gauge with a 3 KB payload.
- [ ] **Step 5: Commit** `feat(nse): decrypt and rewrite notifications`.

---

## Integration

### Task I1: Merge and test end to end

**Files:** Create `Tests/MessagingTests/EndToEndTests.swift`. Modify `README.md` in the plans folder (the cross-plan changes above).

- [ ] **Step 1:** Merge the lanes in the order C, A, D, B. After each merge, run `swift test` in LocalStore and Messaging → PASS.
- [ ] **Step 2: Failing tests**, with full `LiveChatStore` graphs for ana1, ana2, bob1 and bob2 on one `FakeServer`.
  - `TestConversationAcrossFourDevices`:
    - ana1 sends "hi" → bob1, bob2 and ana2 show it
    - bob2 replies with a photo → ana1 auto-downloads it on Wi-Fi
    - ana's status for "hi" reaches `.read` after bob1 opens the chat
  - `TestRevokedDeviceGetsNothing`: bob1 revokes bob2 → ana1's next message reaches bob1 and not bob2.
  - `TestLinkedDeviceJoins`: ana links ana3 with a snapshot → after ana1's reconcile, ana3 receives new messages and has the same contacts.
  - `TestServerReadableEndToEnd`: the FakeServer policy is `server_readable` → every POSTed message has an escrow copy, and ana's chat info encryption is `.serverReadable`.
- [ ] **Step 3:** Run → fix the wiring only, until PASS.
- [ ] **Step 4:** Push → all Apple CI jobs green: core (Linux), packages-macos, ios and macos.
- [ ] **Step 5: Commit** `test(messaging): end-to-end multi-device messaging`.
- [ ] **Step 6:** Do the whole-branch review (superpowers:requesting-code-review, on Opus) with this Review Focus and every lane's `Ruling:` lines. Then the owner reviews the paths flagged in Global Constraints before merging to `main`.

## Not in this plan

- Video messages. `MediaKind.video` exists, but sending video waits for a data-use decision.
- Disappearing messages.
- Group chats (Phase 2).
- Encrypted backups (Phase 2).
- Syncing folders and blocks across own devices after linking.
- Message forwarding.
- Deleting a message for everyone after a time limit. Phase 1 allows deleting for everyone at any time.
