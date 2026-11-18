# Groups, Client Stage: Implementation Plan

> **For agentic workers:** each task is built by one agent on its own branch, test-first, with a commit per step and a push after each commit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group chats on Android, iPhone and Mac: create, join, send and receive, per-member receipts, add, remove, leave, admins, group name and photo.

**Architecture:** A group is one MLS group whose members are devices, exactly like a one-to-one chat; the server holds the account list, roles and a version (`membersVersion`) and refuses commits that break them. The clients keep a copy of the member list, follow it by `members` inbox items, and derive system lines from the difference between two lists. Tasks run in order G1 → G2 → G3, then G4 and G5 in parallel; each platform has its own branch per task.

**Tech Stack:** Kotlin/Compose; Swift 6/SwiftUI; the existing `Messaging`/`messaging` sync code and MLS binding, unchanged.

**Spec:** `docs/superpowers/specs/2026-11-18-people-and-groups-design.md` section 6.7. Server contract: the "Groups" part of `docs/superpowers/specs/2026-10-27-server-design.md` (endpoints, `members` inbox item, error codes `owner_must_transfer`, `group_full`, `forbidden`, `not_found`). Read both, AGENTS.md, and `docs/superpowers/plans/2026-11-18-people-clients.md` (its Global Constraints apply here unchanged, including the Android and Apple command lines).

## Global Constraints

- Everything in the people plan's Global Constraints.
- No change to `mlsffi`, key storage, key-package handling or the mode pin rules. A step that needs one stops and reports.
- A one-to-one chat must behave exactly as before every task: its chat ID stays the peer's account ID, and every existing test passes unchanged unless a signature it uses gains an optional parameter.
- Group chat ID: `g:<conversationID>`. Envelope kind `group {name, photo?, ver}`: name 1–50 code points under the display-name character rules; photo as for a profile (JPEG ≤ 24,576 bytes).
- The server's member list is the authority for who is in a group and who is an admin. A client never shows, counts or trusts a sender that is not in its copy of the list at the message's epoch, and ignores a `group` or `timer` envelope from a non-admin.
- No calls, presence or "last seen" in a group. Typing shows "%@ is typing…".
- Limits: 2–99 other people at creation, 100 members in total.

## Review Focus

1. A device removed from a group: after the `members` item it sends nothing, shows "You were removed", and cannot be made to display a later message (a replayed or late commit or message is refused).
2. A member's device that misses a `members` item (offline) and comes back: it fetches the list, removes the departed accounts' devices from the MLS group in its next commit, and its system lines are derived from the list difference, each once.
3. Two admins change the group at the same moment (add and remove, or two renames): the first commit per epoch wins on the server; the loser retries on the new state without duplicating members or system lines; the higher `ver` name wins.
4. A message from a member the receiver has blocked: collapsed, no notification, and never counted as unread.
5. A `g:`-prefixed ID reaching code that assumes a contact (calls, safety number, block, profile lookup, notifications reply): no crash, the control is absent or the action is refused.

Each task below owns the test for the lines that concern it.

---

### Task G1: Data model (branches `feat/g1-android-groups-model`, `feat/g1-apple-groups-model`)

**Produces — core (pure, same test table both platforms):**
- `ChatID.isGroup(id)`, `ChatID.group(conversationID)`, `ChatID.conversationID(id) -> String?`.
- `GroupRole`: `owner, admin, member`; `canAdd`, `canRemove(target: GroupRole)`, `canSetRoles`, `canEditInfo`, `canSetTimer` (owner and admin edit info and timer; admin removes only members; only the owner sets roles).
- `MemberDiff.lines(old: [Member], new: [Member], actor: accountID?, me: accountID) -> [SystemLine]` with lines `added(by, who)`, `removed(by, who)`, `left(who)`, `youWereRemoved`, `madeAdmin(who)`, `noLongerAdmin(who)`; deterministic order (by account ID); `actor` nil gives the forms without an actor.
- `GroupNameRule.clean(name) -> String?` (1–50, display-name character rules).
- `GroupMerge.accept(currentVer?, incomingVer, senderRole) -> Bool`.

**Produces — envelope:** kind `group {name, photo?, ver}` with shared vectors `server/internal/vectors/envelope_groups.json` (Swift and Kotlin tests read it).

**Produces — store (one migration, next free version):** `chats.kind` (`direct` default, `group`), `chats.conversation_id`, `chats.title`, `chats.photo`, `chats.group_ver`, `chats.members_version`, `chats.my_state` (`member`, `removed`, `left`); `Chat.contactID` optional; table `group_members(chat_id, account_id, role, primary key (chat_id, account_id))`; table `receipts(message_id, account_id, kind, at, primary key (message_id, account_id, kind))`; `messages.sender_id` if not already present. API: `upsertGroup`, `setGroupMembers(chatID, members, version) -> old members` (ignores a version not higher than stored), `groupMembers(chatID)`, `setGroupInfo`, `setMyState`, `addReceipt`, `receipts(messageID)`.

**Produces — server calls:** `createGroup(accounts) -> {conversationID, mode}`, `conversation(id) -> {kind, members:[{account, role}], membersVersion, epoch, mode}`, `addAccounts`, `removeAccount`, `setRole`, and the `members` inbox item decoded as an event `{conversationID, membersVersion}`.

- [ ] Failing tests for the pure functions (every role pair for `canRemove`; diff of add+remove+role change in one step; me removed; empty diff → no lines); implement; pass.
- [ ] Envelope vectors and tests; migration test from the previous version with existing one-to-one rows (their `kind` is `direct`, `contactID` kept).
- [ ] Store tests: stale `members_version` ignored; receipts unique per member and kind.
- [ ] Every place that force-reads `contactID` handles nil without a crash (Review Focus 5): list them in the report with what each now does.

### Task G2: Create and join (branches `feat/g2-*-groups-create`)

- [ ] `GroupCreator.create(accounts, name, photo)`: `createGroup`, claim key packages for every device of every member, one commit with the welcomes, then send `group {name, photo, ver: 1}` and own `profile`. Failing model tests with the existing sync fakes: 3 members with 2 devices each → one commit with 6 adds; a member with no key packages → the group is still created with the others and the person is told "%@ couldn't be added yet"; server `not_found`/`group_full` surfaced.
- [ ] Join: a welcome whose conversation is `group` (from `conversation(id)`) creates the chat `g:<id>`, stores the member list, inserts "%@ added you". A welcome for a group the server says this account is not in is dropped. Mode pin rule applied as for one-to-one; refusal message per the spec.
- [ ] Own new device joining existing groups follows the existing device-sync path; test that a second device of a member is added by that member only.
- [ ] Screens: New chat > "New group": pick contacts (at least 2), then name and optional photo, Create. Strings: "New group", "Add people", "Group name", "Create", "Choose at least 2 people", "%@ added you", "%@ couldn't be added yet".
- [ ] UI test (sample mode): create a sample group of two contacts; it appears in the chat list with its name.

### Task G3: Send, receive, receipts, notifications (branches `feat/g3-*-groups-messages`)

- [ ] Send and receive every content kind in a group through the existing outbox and inbox paths keyed by conversation; `messages.sender_id` set from the MLS sender's account; a sender not in the stored list at processing time → fetch the list once, then drop if still absent (Review Focus 1). Tests with three fake members.
- [ ] Receipts: delivered and read receipts sent to the group as today; stored per member in `receipts`; the bubble tick shows delivered/read only when every other current member has; Message info lists "Read by" and "Delivered to" with names and times. Pure function `GroupTicks.state(receipts, members, me) -> sent | delivered | read` with tests (a member who left does not hold the tick back).
- [ ] Chat: sender name and tile above the first bubble of each run of incoming messages; chat list preview "Name: text" ("You: text" for own); no call buttons; typing "%@ is typing…" (two or more: "%1$d people are typing…").
- [ ] Notifications: title is the group name, body "Name: text"; a muted group stays silent; reply from the notification goes to the group; the extension / Android path resolves `g:` IDs. Blocked sender: no notification, not counted unread, bubble collapsed to "Message from a blocked contact" with "Show" (Review Focus 4).
- [ ] Disappearing-message timer and `group` envelopes accepted only from an owner or admin.
- [ ] UI test (sample mode): the sample group shows two senders' names; Message info lists both members.

### Task G4: Membership changes (branches `feat/g4-*-groups-members`)

- [ ] `GroupSync.onMembers(conversationID, membersVersion)`: fetch the list, `setGroupMembers`, write `MemberDiff` lines once, and when devices of non-members are still in the MLS group, commit their removal (any member may; first commit wins, a rejected commit is rebuilt on the new epoch). Tests: missed items (version jumps by 3) → one fetch, correct lines; two clients racing to remove → one commit lands, no duplicate line (Review Focus 2, 3).
- [ ] Add members: `addAccounts`, then claim key packages and commit the adds with welcomes; a server-added account with no device added yet is shown as a member and its devices are added by the next client that sees them missing.
- [ ] Remove, leave, set role, transfer ownership: the server call first, then the MLS removal commit; `owner_must_transfer` → "Choose a new owner before you leave."; `forbidden` → "You can't do that in this group."; `group_full` → "This group is full."
- [ ] Being removed or leaving: `my_state` set, own MLS group state for it deleted, the chat read-only with "You're no longer in this group"; later items for that conversation are dropped (Review Focus 1). Deleting the chat removes its rows and files.
- [ ] Rename and photo change: `group` with `ver + 1`; receivers apply through `GroupMerge`; lines "%@ changed the group name to “%@”", "%@ changed the group photo".

### Task G5: Group screens (branches `feat/g5-*-groups-screens`; the lead reviews)

- [ ] Group chat info: photo, name (owner and admins edit), member count, member list with "Owner"/"Admin" labels sorted owner, admins, members by name; "Add members"; per-member menu by `GroupRole` rules ("Make admin", "Remove as admin", "Make owner", "Remove from group", "Message", "View safety number"); "Disappearing messages" (owner and admins); Mute; "Media and files"; "Leave group" with a confirmation; after leaving, "Delete chat".
- [ ] The only place an encryption type is shown stays chat info, in the existing wording.
- [ ] iPad/tablet two-pane and Mac: the group info opens as the existing chat info does; Mac "Chat Info" ⌘I works for a group; call commands are disabled for a group.
- [ ] VoiceOver/TalkBack: each member row reads "Name, role"; menus are reachable as accessibility actions.
- [ ] UI tests (sample mode): group info lists members with roles; a plain member sees no "Add members"; Leave shows the confirmation.

## Merge

The lead merges in order G1, G2, G3, then G4 and G5. G2, G3 and G4 each get an Opus review (MLS usage, authorization, the Review Focus lines); the lead reviews every screen. Android merges after local tests; Apple in one push per task, then CI, then fix forward. The owner's review list in the spec (section 9) covers this whole stage.
