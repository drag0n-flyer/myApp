# People and Groups, Server Stage: Implementation Plan

> **For agentic workers:** each task is built by one agent on its own branch, test-first, with a commit per step and a push after each commit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The server side of typing/presence relay, disappearing-message expiry, contact links, the shared display-name rule, and group conversations with roles.

**Architecture:** Additions to the existing modules (`identity`, `social`, `realtime`, `delivery`, `serverinfo`) in `server/internal`, with new numbered SQL migrations. Nothing changes for existing one-to-one clients: every new field is optional and every existing endpoint keeps its behaviour for `kind = direct`.

**Tech Stack:** Go, Postgres, the repository's own `httpx`, `ratelimit`, `testkit`.

**Spec:** `docs/superpowers/specs/2026-11-18-people-and-groups-design.md` (sections 6.1, 6.2, 6.5, 6.6, 6.7). Also read `docs/superpowers/specs/2026-10-27-server-design.md` and AGENTS.md.

## Global Constraints

- No new Go module dependencies. Follow the existing patterns for handlers, errors (`httpx`), rate limits, migrations numbering and tests.
- Tests: `cd server && flock /tmp/myapp-heavy.lock timeout 900 env TEST_DATABASE_URL='postgres:///postgres?host=/var/run/postgresql' go test ./...`. Write the failing test first and see it fail.
- Existing tests must pass unchanged, except where a test asserts a rule this plan replaces (say which in the report).
- Never log tokens, ciphertext or names. Tokens are 16 random bytes from `crypto/rand`, stored as a hash, compared in constant time.
- Do not touch the push gateway, APNs/FCM credentials handling, or `.github/workflows`.
- Commits: plain messages, no attribution trailers. Push after each commit. Update `docs/superpowers/specs/2026-10-27-server-design.md` with each new endpoint, frame, table and error code in the same style as the existing entries.

## Review Focus

1. A device that is not a member (or was just removed) sends `conv.ephemeral` or a commit: refused, nothing relayed.
2. A contact link redeemed twice at the same moment: exactly one succeeds.
3. A blocked account redeeming a link gets the same answer, status and timing class as an expired link.
4. In a group, a member (not admin) tries to remove another account, or names another member's device in `removeDevices`: refused. The owner cannot be removed; the last admin leaving is handled.
5. Every existing one-to-one flow behaves as before (the existing delivery tests are the proof).

---

### Task S1: Display-name rule and shared vectors (spec 6.6)

Branch `feat/s1-server-names`.

- [ ] Add `server/internal/vectors/display_names.json`: cases `{input, clean}` where `clean` is null for refused. Take every case from the clients' tests (`apps/apple/Packages/AppCore/Tests/AppCoreTests/DisplayNameRuleTests.swift`, `apps/android/core/src/test/.../DisplayNameRuleTest.kt`) and add: 40 and 41 code points, only spaces, NBSP trimming, U+202E inside, U+200B only, U+2028 inside, an emoji-only name, a combining sequence.
- [ ] Failing test `TestValidDisplayNameVectors` in `identity` reading the file.
- [ ] `identity.ValidDisplayName(s string) (clean string, ok bool)` in `names.go`, matching the clients' `DisplayNameRule.clean` exactly (read both client implementations; where they differ on an input, report it and follow the Swift one).
- [ ] `patchSettings` uses it and stores the cleaned name; the error code stays the existing one for a bad name. Handler tests: bidi control refused, 41 code points refused, padded name stored trimmed.
- [ ] Add a Swift test and a Kotlin test that read the same JSON file (path relative to the repository) and check `DisplayNameRule.clean`; run AppCore `swift test` and `:core:test`.

### Task S2: Contact links and pages (spec 6.5)

Branch `feat/s2-server-contact-links`.

- [ ] Migration: `contact_links(token_hash bytea primary key, account_id, created_at, expires_at, used_at null, used_by null)`.
- [ ] Failing handler tests, then `POST /v1/contact-links` → `{token, expiresAt}` (7 days; at most 20 live per account, then `too_many`); `POST /v1/contact-links/redeem {token}` → `{accountID, username, displayName}` once.
- [ ] Redeem rules with tests: own link → `own_link`; unknown, expired or used → `expired`; owner has blocked the caller → `expired`, indistinguishable; concurrent redeem → one winner (single `UPDATE … WHERE used_at IS NULL RETURNING`); rate limited with the username-lookup limiter; a deleted owner account → `expired`.
- [ ] Expired links are removed by the existing hourly sweep.
- [ ] Pages `/u/{username}` and `/c/{token}` in the style of the existing `/invite/{code}` page (no account data on the page), and both paths added to the app-link association files served by `serverinfo`. Tests for the association JSON.

### Task S3: Ephemeral frame and message expiry (spec 6.1, 6.2)

Branch `feat/s3-server-ephemeral`.

- [ ] Failing tests with the realtime test harness, then frame `conv.ephemeral {conversationID, epoch, ciphertext}`: the sender must be a member device; relayed as `conv.ephemeral {conversationID, epoch, from: {account, device}, ciphertext}` to member devices connected now, except devices of the sender's account; nothing stored, no push; in a `direct` conversation not relayed to an account that blocked the sender; ciphertext ≤ 4 KiB; 30 per minute per device per conversation, excess dropped silently; acknowledged like other frames.
- [ ] Tests: non-member refused; offline member gets nothing later; no inbox row and no push job are created (assert on the tables).
- [ ] `POST /messages` accepts optional `expiresAt` (RFC 3339, future, at most 366 days ahead; otherwise `bad_request`); stored on the inbox rows; the hourly sweep deletes rows past it; an expired row is never delivered. Tests for each.

### Task S4: Group conversations, roles and commit rules (spec 6.7)

Branch `feat/s4-server-groups`. The largest task; needs the strictest review.

- [ ] Migration: `conversations.kind text not null default 'direct'`, `conversations.members_version bigint not null default 0`, `conversation_accounts.role text not null default 'member'` (existing rows: both `member`; direct conversations ignore roles).
- [ ] Failing tests, then create: `POST /v1/conversations {kind: "group", accounts: [1..99 others]}`; creator `owner`; duplicates and self refused; unknown accounts refused without saying which; `kind` omitted behaves exactly as today.
- [ ] `GET /v1/conversations/{id}` adds `kind`, `members: [{account, role}]`, `membersVersion` (members only may read).
- [ ] Membership endpoints with tests for every role combination: `POST /v1/conversations/{id}/accounts {accounts}` (owner/admin; cap 100 total); `DELETE /v1/conversations/{id}/accounts/{account}` (owner/admin removes a member; admin cannot remove an admin or owner; anyone removes themselves = leave; an owner leaving with other members present must first transfer, else `owner_must_transfer`; the last member leaving deletes the conversation); `PUT /v1/conversations/{id}/accounts/{account}/role {role}` (owner only; setting another account to `owner` makes the caller `admin`). Each bumps `membersVersion`, removes a removed account's devices from `conversation_devices` at once, and notifies member devices with a stored, pushed delivery item of a new kind `members` carrying `{membersVersion}` only (clients then `GET`).
- [ ] Commit rules for `group`: `addDevices` only for devices of current member accounts; `removeDevices` only for the sender's own account's devices or devices whose account is not a current member; violations → `forbidden`. Welcomes go only to devices of current members. `direct` conversations keep today's rules. Tests for each violation and for the happy paths (own-device add/remove by a plain member still works).
- [ ] Blocking: in a `group`, blocks do not filter adds, fan-out or pushes. Test that a blocker still receives group messages, and that `direct` behaviour is unchanged.
- [ ] Removed-member tests: after removal the account's devices can no longer fetch conversation state, send messages, commits or ephemeral frames; items already in their inbox stay deliverable.

## Merge

The lead merges each task after an Opus review. S4 gets two reviews. The server CI workflow must be green before the client stages start using an endpoint.
