# Security, Docs, and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Coding agents run on Sonnet 5.5; the final review uses Opus.

**Goal:** Produce everything the invite-only beta needs beyond working code:
- a threat model and a protocol spec, with shared test vectors
- the missing CI security gates
- the disclosure policy, privacy policy and terms
- export compliance, App Store readiness, and the beta server runbook
- the independent audit request and branch protection
- a beta gate that ties it all to evidence

**Architecture:** Mostly Markdown documents under `docs/`, each with required sections and a machine check, so "done" is checkable. A few small code pieces support them:
- a Go generator and verifier for the protocol test vectors, plus a Swift verifier
- a server route that serves operator documents (`security.txt`, privacy, terms)
- deployment scripts with a backup-and-restore drill in CI
- scripts that check release config and documents

Steps that need secrets, accounts or legal judgment are **owner-run** and marked 👤. Agents never handle the APNs key, signing certificates, TURN secrets or production credentials (AGENTS.md).

**Tech Stack:** Markdown; JSON test vectors; Go 1.26 (`server/`); Swift 6 and Swift Testing (macOS CI); Python 3 standard library for doc checks; GitHub Actions; Docker Compose; Caddy.

**Spec:** [GOAL.md](../../../GOAL.md) §5, §13, §16, §17; [server design](../specs/2026-10-27-server-design.md) (§7.2 escrow, §7.3 TURN, §7.4 push, §8 security rules, §9 metadata); [roadmap contracts](README.md).

## Global Constraints

- **Documents need human sign-off.** Every legal document starts with the banner `> **Draft — needs lawyer review before use.**`. Every security document is owner-reviewed (CODEOWNERS, Task 13).
- **The protocol spec references, never copies.** For client↔server endpoints it links to the server design's §7, which stays the single source of truth.
- **Test vectors** live in `docs/protocol/test-vectors/*.json`. They are generated only by the Go generator (Task 4), never edited by hand, and both Go and Swift verify them.
- **New tools need the owner's approval** (AGENTS.md). Tasks 1 and 6 list every tool, and approving this plan approves them. Pin exact versions; never use `latest`.
- **No secrets** in the repo, in CI logs, or in agent hands. Scripts read secrets from files or env that the owner creates. `.env.example` holds only placeholders.
- **Brand placeholder** `[App]` and bundle ID `org.example.app` (roadmap) until the owner picks a brand.
- **Commits:** plain messages, no `Co-Authored-By` or AI trailers.
- **Branches:**
  - Work on `docs/security-release`, branched from `main` after plans 1 and 2 merge.
  - Tasks 1–3, 6, 8 and 12 may start earlier on a branch from `feat/server`, and are rebased later.

## Decisions I made — owner to confirm

1. **Tools (Tasks 1 and 6):**
   - `markdownlint-cli2` (MIT) and `lychee` (Apache-2.0/MIT) for document checks
   - `gitleaks` (MIT, CLI binary, not the GitHub Action, whose org use needs a license key) for secret scanning, plus GitHub secret scanning and push protection once the repo exists
   - `go-licenses` (Apache-2.0) for Go license scanning
   - a repo script over `Package.resolved` for Swift license allowlisting
   - `gremlins` (Apache-2.0) for Go mutation testing, weekly and not blocking
   - Swift mutation testing deferred, because Muter needs macOS minutes; revisit after plan 7
2. **Solo-owner review problem.** If agents push and open pull requests with the owner's own GitHub identity, GitHub never lets the owner approve them, so "required code-owner review" would block every merge. Proposal: agents open PRs from a **machine-user account** (for example `velixio-bot`, with a fine-grained token scoped to this repo), and the owner approves. The alternative is a ruleset that lets the owner bypass. Task 13 implements the machine user unless the owner picks the bypass.
3. **MLS profile:**
   - cipher suite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (0x0001) for all groups
   - basic credentials holding `"<accountID>/<deviceID>"` (roadmap)
   - KeyPackage lifetime 90 days
   - one last-resort KeyPackage per device
   - the exporter label `"call-fingerprint-v1"` is reserved for call-key checks

   Owner review: cryptography.
4. **Test vector keys are fixed, published test keys.** They come from fixed seeds (`000102…1f`) and are marked `TEST ONLY` in every file. Ed25519 is deterministic, so signature vectors are exact. HPKE sealing is randomized, so vectors pin `sealed` bytes for **opening** tests, and round-trip tests cover sealing.
5. **Operator documents (`security.txt`, privacy, terms) are served by the app server** from `APP_DOCS_DIR`, so each self-hoster publishes their own. The project's beta server uses the drafts written here.
6. **Export classification (working assumption, for the lawyer to confirm):**
   - the apps are mass-market encryption items (ECCN 5D992.c) under License Exception ENC §740.17(b)(1)
   - `ITSAppUsesNonExemptEncryption = YES`, because they ship MLS (mls-rs) and DTLS-SRTP inside our own WebRTC build, not only OS-provided cryptography
   - the French declaration is uploaded to App Store Connect if the app is offered in France
7. **Beta topology:** one app VM (Caddy + appserver + Postgres), two TURN relay VMs in different regions, and one small push-gateway VM. Hetzner is the example provider (included traffic), not a requirement.

## Review Focus

1. **A vector both sides "pass" while it is wrong** (for example, Go and Swift both assume `sealed = ct ‖ enc` while the real layout is `enc ‖ ct`). Task 4 asserts the exact byte layout (`len == 32 + len(pt) + 16`, `enc` first), and Task 5 opens Go-sealed bytes in Swift and Swift-sealed bytes in Go.
2. **A restore that "succeeds" but loses data**, such as attachments missing because only Postgres was backed up. Task 11's CI drill writes an account, a message row and an attachment, restores everything into a fresh stack, and checks all three.
3. **An operator document a self-hoster can't override, or a missing one that crashes the server.** Task 7 tests that a missing `APP_DOCS_DIR` file returns 404, not 500, and that a different directory serves different content.
4. **The release check passing on a project that lacks a required key**, because a pattern matched a comment. Task 10's script parses `project.yml` and `.entitlements` as YAML and plist, never with grep, and its tests include a commented-out key that must still fail.
5. **A threat model row with no real mitigation.** Task 2's checker rejects any row whose mitigation isn't a `plan N TaskID` reference that exists in that plan file, or an `OPEN-n` ID listed under "Open risks".

---

## Ordering

| Task | Can start | Blocked by |
|---|---|---|
| 1 Doc checks in CI | now | tool approval |
| 2 Threat model | now | — |
| 3 Protocol spec | now | — |
| 4 Test vectors (Go) | plan 2 D1, C4 and B4 merged | — |
| 5 Test vectors (Swift) | plan 6 ServerKit and plan 4 SecureMessaging packages exist | Task 4 |
| 6 CI security gates | now | tool approval |
| 7 Operator documents route + security.txt | plan 2 merged | — |
| 8 Privacy policy and terms | now | — |
| 9 Export compliance | now (memo); plan 1 merged (`project.yml` key) | — |
| 10 App Store readiness | plan 1 merged | — |
| 11 Beta server operations | plan 2 merged | — |
| 12 Independent security review | now (draft); submit after plans 7–8 | — |
| 13 Code owners and branch protection | GitHub repo exists | owner decision 2 |
| 14 Beta gate | last | everything |

One agent can run the whole plan in this order. Tasks 2, 3 and 8 can run in parallel with Task 6 if a slot is free (the roadmap allows 5 agents at once).

---

### Task 1: Document checks in CI

**Files:**
- Create: `.markdownlint-cli2.jsonc`, `lychee.toml`, `.github/workflows/docs.yml`, `scripts/check-docs.sh`

**Interfaces — Produces:** `scripts/check-docs.sh [paths…]` runs, in order:
1. `npx --yes markdownlint-cli2@0.18.1 <paths>`
2. `lychee --offline --no-progress <paths>`, which checks relative links and anchors
3. `python3 scripts/check-threat-model.py`, but only if that file exists (Task 2)

It exits non-zero on the first failure. CI runs it with `--offline` off weekly (external links) and on every PR (offline).

- [ ] **Step 1: Write the configs.**
  - markdownlint: disable `MD013` (line length) and `MD033` (inline HTML, used in mockups); allow siblings-only duplicate headings (`MD024: {siblings_only: true}`).
  - lychee: exclude `localhost`, `chat.example.org` and `example.org`.
- [ ] **Step 2:** Run `scripts/check-docs.sh GOAL.md AGENTS.md docs/superpowers/`. Expected: it reports existing findings. Fix only real broken links and anchors in those files. Record each style rule you have to relax as a `Ruling:` line in the ledger.
- [ ] **Step 3:** Run it again. Expected: exit 0.
- [ ] **Step 4:** Write `docs.yml`. It runs on `pull_request` and `push` for `**.md`, plus `schedule: '0 6 * * 1'`, which runs with online link checks. Pin lychee as the release binary `v0.18.1`, with its checksum verified in the workflow.
- [ ] **Step 5: Commit.** `ci: markdown lint and link checks`

### Task 2: Threat model

**Files:**
- Create: `docs/security/threat-model.md`
- Create: `scripts/check-threat-model.py`, `scripts/test_check_threat_model.py`

**Required sections** of `threat-model.md`, in order:
1. **Scope and method:** STRIDE per component, as of plan versions dated 2026-10-27.
2. **Assets:**
   - message plaintext, MLS private state, device signing keys
   - session tokens, passkeys and email-code flows, link secrets
   - push tokens and handles, the escrow private key, the APNs key, the TURN secret
   - the gateway HPKE key and the server Ed25519 push key
   - metadata (server design §9)
   - call media, IP addresses
3. **Actors:**
   - remote attacker
   - malicious user
   - malicious or compromised self-hosted server operator
   - compromised project push gateway
   - Apple (APNs)
   - network observer
   - TURN relay operator
   - stolen or unlocked device
   - malicious linked-device scanner (QR shoulder-surf)
   - supply-chain attacker (dependencies, CI, AI-written code)
4. **Trust boundaries:** a Mermaid diagram plus a list:
   - device ↔ server (TLS)
   - server ↔ gateway (signed)
   - gateway ↔ APNs
   - device ↔ device media (DTLS-SRTP, direct or through TURN)
   - app ↔ NSE (shared app-group storage)
   - admin CLI ↔ database
5. **Threats:** one table per component: server API, WebSocket, delivery/MLS DS, escrow, push gateway, APNs path, TURN/coturn, iOS app, NSE, macOS app, device linking, admin and operations, build and CI. Columns: `ID | STRIDE | Threat | Impact | Mitigation | Residual`. IDs look like `SRV-S-01`.
6. **Mode B (server-readable) specifics:** what the operator can read, the audit log, retention, and why Mode A can't be downgraded (plan 2 Review Focus 1).
7. **Metadata exposure:** copied from server design §9, plus the gateway and Apple views.
8. **Open risks:** `OPEN-n` items. At minimum:
   - no public audit of mls-rs (GOAL §5)
   - display names in plain text (server spec §2 item 6)
   - the in-memory call state and rate limits are lost on restart
   - phone discovery is postponed
   - AI-written code in sensitive paths
9. **Review log:** dated owner sign-offs.

**`check-threat-model.py`** (standard library only):
- Every row of a table under "Threats" needs a non-empty `Mitigation` cell of the form `plan <N> <TaskID>` (e.g. `plan 2 A3`), several separated by `;`, or `OPEN-<n>`.
- A `plan N TaskID` reference must match a `### Task <TaskID>:` heading in the plan file whose row number is `N` in `docs/superpowers/plans/README.md`.
- Every `OPEN-n` must appear under "Open risks".
- IDs are unique.

- [ ] **Step 1: Write failing tests** in `test_check_threat_model.py`, using `unittest` with temporary fixture files:
  - `test_rejects_empty_mitigation`
  - `test_rejects_unknown_task`: `plan 2 Z9` → error naming `Z9`
  - `test_rejects_open_without_entry`
  - `test_rejects_duplicate_ids`
  - `test_accepts_valid`, with two rows: `plan 2 A3` and `OPEN-1`
- [ ] **Step 2:** `python3 -m unittest scripts/test_check_threat_model.py`. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement the checker.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Write `threat-model.md`.** It needs at least 6 threats per component table, and every mitigation must cite real task IDs from plans 2–8. Cite plans 3–8 only if they exist when you write; otherwise use `OPEN-n` and note "update when plan N lands".
- [ ] **Step 6:** `scripts/check-docs.sh docs/security/`. Expected: exit 0.
- [ ] **Step 7: Commit.** `docs(security): threat model with mitigation checker`. 👤 The owner reviews and adds a dated line to the review log.

### Task 3: Protocol specification

**Files:** Create `docs/protocol/README.md`, `mls-profile.md`, `device-linking.md`, `calls.md`, `push.md`, `escrow.md` and `versioning.md` in `docs/protocol/`.

**Required content:**

- **`README.md`:**
  - Index, plus "Endpoints and frames: see [server design §7](../superpowers/specs/2026-10-27-server-design.md#7-http-api)".
  - Message content format: "see plan 7 (client messaging), `MessageBody` schema". Until plan 7 lands, leave a `TODO(plan 7)` link that Task 14 checks is gone.
  - Conventions: base64url without padding, and UTF-8 everywhere.
- **`mls-profile.md`:**
  - cipher suite 0x0001
  - credential type basic, holding `"<accountID>/<deviceID>"`
  - required capabilities and extensions (none beyond the RFC 9420 defaults, unless plan 4 adds them)
  - KeyPackage lifetime 90 days, a refill threshold below 10 left (server `GET /v1/keypackages/count`), and one last-resort KeyPackage
  - one MLS group per conversation; group ID = 16 random bytes chosen by the creating device
  - commit flow: the device sends a commit and merges it only after HTTP 200 (`commitAccepted`); `stale_epoch` → `commitRejected`, fetch the inbox, process, and retry once
  - on `device.revoked`, any remaining member device commits a Remove for that device's leaf in every shared group, within 24 h of coming online
  - a linked device joins existing conversations through a Welcome from a device that already belongs (plan 7)
  - epochs and the server-side future-epoch rule: the server rejects only an epoch above the current one
  - exporter labels: `"call-fingerprint-v1"`
  - post-quantum note: ReInit to a PQ suite later (GOAL §5)
- **`device-linking.md`:**
  - a sequence diagram of `POST /v1/link` → QR `linkID.secret` → approve with `sealed` → claim
  - what goes in `sealed`: an HPKE-sealed JSON blob to an X25519 key the new device includes in its QR. **This needs plan 6's linking task: the QR becomes `linkID.secret.pubkeyB64`.**
  - what the new device gets: account ID, display name, contact list, no message history (history needs backups, Phase 2)
  - threats: link to the threat model's linking rows
- **`calls.md`:**
  - the frames (link to the spec)
  - the `payload` inside each frame is an MLS application message in the 1:1 conversation's group, whose plaintext is JSON `{"v":1,"kind":"offer"|"answer"|"ice"|"renegotiate"|"mode","sdp"?,"candidate"?,"fingerprint"?:"sha-256 AB:CD:…","mode"?:{"preference":…}}`
  - the receiver must check that the DTLS fingerprint in the SDP equals the MLS-authenticated `fingerprint` and abort the call otherwise
  - relay policy (`iceTransportPolicy = relay` per the IP-hiding setting)
  - TURN credential lifetime 12 h
  - ICE restart rules from GOAL §6: after 2–3 s disconnected, or on a network change; give up after about 30 s
- **`push.md`:**
  - the sealed handle: HPKE suite, `info = "push-handle-v1"`, wire layout `enc (32 bytes) ‖ ciphertext`, and the plaintext JSON keys `t`, `k`, `e`, `b`
  - padding: buckets and the 2-byte length
  - the server→gateway signature string
  - gateway checks and limits (link to the spec)
  - what the NSE does with `d`: unpad, then MLS-process, or an empty payload → fetch the inbox
  - the iOS VoIP rule: report to CallKit immediately
- **`escrow.md`:** HPKE with `info = "escrow-v1"`, plaintext JSON `{conversationID, senderAccountID, sentAt, body}` (`body` = the same `MessageBody` as the MLS plaintext), the layout `enc ‖ ct`, the server checks (`escrow_required`/`escrow_forbidden`), and export with the audit log.
- **`versioning.md`:** the `protocol` integer in `GET /v1/server`, and what clients do on an unknown higher version (show "Update [App] to use this server"), plus the federation-ready identifier rule: IDs are server-local UUIDs, and a future federated ID is `<uuid>@<host>`.

- [ ] **Step 1: Write the six documents.** Each one ends with a "Test vectors" line that links to the matching Task 4 file, or says "none".
- [ ] **Step 2:** `scripts/check-docs.sh docs/protocol/`. Expected: exit 0.
- [ ] **Step 3: Commit** `docs(protocol): MLS profile, linking, calls, push, escrow, versioning`. 👤 The owner reviews; it touches protocol state machines.

### Task 4: Test vectors — generator and Go verifier

**Files:**
- Create: `server/internal/vectors/vectors.go` (types and loading), `server/internal/vectors/vectors_test.go`
- Create (generated): `docs/protocol/test-vectors/push-padding.json`, `request-signature.json`, `sealed-handle.json`, `escrow.json`, `turn-credential.json`

**Interfaces — Consumes:** `pushproto.Pad`, `pushproto.Unpad`, `pushproto.SigningString`, `pushproto.Sign`, `pushproto.Verify`, `pushproto.OpenHandle`, `pushproto.SealHandle` (plan 2 D1); `turn.Credential` (C4); `delivery.SealEscrow`, `delivery.OpenEscrow` (B4).

**File formats.** Every file has `"version":1` and `"note":"TEST ONLY — published keys"`.

| File | Fields |
|---|---|
| `push-padding.json` | `cases:[{name, payloadHex, paddedHex}]`. Lengths 0, 1, 254, 255, 1000, 3070 and 3071; the 3071 case pads the *empty* payload |
| `request-signature.json` | `seedHex` (32 bytes `000102…1f`), `publicKeyB64`, `cases:[{method, path, timestamp, bodyB64, signingString, signatureB64}]`. At least 3 cases, including an empty body |
| `sealed-handle.json` | `suite:{kem:"DHKEM(X25519, HKDF-SHA256)", kdf:"HKDF-SHA256", aead:"ChaCha20Poly1305"}`, `info:"push-handle-v1"`, `recipientPrivateKeyHex`, `recipientPublicKeyB64`, `cases:[{plaintextJSON, sealedB64}]`, `negative:[{name, sealedB64, info}]` where opening must fail: wrong info, one flipped byte, truncated |
| `escrow.json` | the same shape, with `info:"escrow-v1"` and plaintexts following the escrow schema |
| `turn-credential.json` | `cases:[{secret, username, credential}]`. Must include `{"s3cret","1767225600:9f86d081","4W/l/i5GJz0koAY8s6EK0JkL88Q="}` |

- [ ] **Step 1: Write the failing tests** in `vectors_test.go`. They read `../../../docs/protocol/test-vectors/*.json`. With `-update`, `TestGenerate` (re)writes the files from fixed seeds instead of checking them.
  - `TestPadding`: `hex(Pad(payload)) == paddedHex`, and `Unpad` round-trips (empty for the 3071 case).
  - `TestRequestSignature`: `SigningString` equals `signingString`; `Sign` produces `signatureB64` exactly (Ed25519 is deterministic); `Verify` accepts it.
  - `TestSealedHandle`:
    - every case opens to `plaintextJSON`
    - every negative case fails
    - `len(sealed) == 32 + len(plaintext) + 16`
    - a fresh `SealHandle` → `OpenHandle` round-trips
  - `TestEscrow`: the same checks with the escrow functions.
  - `TestTURNCredential`: every case.
- [ ] **Step 2:** `cd server && go test ./internal/vectors/`. Expected: FAIL (files missing).
- [ ] **Step 3:** Implement the generator under `-update`, then run `go test ./internal/vectors/ -update`. Expected: 5 files written.
- [ ] **Step 4:** `go test ./internal/vectors/` (without `-update`), then `git status --porcelain docs/protocol/test-vectors`. Expected: PASS, and a second `-update` run leaves no diff, except in the HPKE `sealed` fields. The generator must keep existing `sealed` values if they still open, so that the files are stable.
- [ ] **Step 5: Commit** `test(protocol): shared test vectors with Go generator and verifier`.

### Task 5: Test vectors — Swift verifier

**Files:**
- Create: `apps/apple/Packages/ServerKit/Tests/ServerKitTests/ProtocolVectorTests.swift`
- Create: `apps/apple/Packages/SecureMessaging/Tests/SecureMessagingTests/EscrowVectorTests.swift`, only if plan 7 places escrow sealing there; otherwise this file goes with whichever package owns `Escrow.seal`

**Interfaces — Consumes (roadmap change requested, see the report):**
- ServerKit: `PushPayload.unpad(_ data: Data) throws -> Data` and `PushHandle.seal(_ handle: PushHandle.Plain, gatewayKey: Data) throws -> Data`, where `Plain` has `token`, `kind`, `env`, `bundle`
- The owner of escrow: `Escrow.seal(_ plaintext: Data, serverKey: Data) throws -> Data`
- CryptoKit `HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly`

The vector JSON is read from the repo with `#filePath`-relative URLs (`../../../../../../docs/protocol/test-vectors`), not copied into the package.

- [ ] **Step 1: Write the failing tests:**
  - `paddingMatchesVectors`: `unpad(paddedHex)` equals `payloadHex` (an empty payload for 3071).
  - `goSealedHandlesOpenInSwift`: open each `sealedB64` with `HPKE.Recipient`, built from `recipientPrivateKeyHex`, the suite and `info`, with `enc` = the first 32 bytes; the result equals `plaintextJSON`. Every negative case throws.
  - `swiftSealedHandleOpensWithVectorKey`: `PushHandle.seal` to `recipientPublicKeyB64`, then open in the test with the vector's private key; it round-trips, and `count == 32 + plaintext + 16`.
  - `escrowVectors`: the same two checks for escrow.
  - `swiftSealedOutputForGo`: writes `docs/protocol/test-vectors/swift-sealed.json` (`{handle:{…}, escrow:{…}}`), only when the environment variable `WRITE_SWIFT_VECTORS=1` is set.
- [ ] **Step 2:** Add `TestSwiftSealedOpensInGo` to Task 4's `vectors_test.go`. It skips if `swift-sealed.json` is absent, and opens both values otherwise.
- [ ] **Step 3:** Run `cd apps/apple/Packages/ServerKit && swift test --filter ProtocolVectorTests` in macOS CI (`apple.yml` core job). Expected: FAIL until the ServerKit functions exist. If plan 6 already built them, it should pass at once; that is acceptable here because the vectors, not the code, are under test. Record a `Ruling:`.
- [ ] **Step 4:** Make it pass. If a mismatch shows up, fix the side that disagrees with `docs/protocol/push.md`, never the vector.
- [ ] **Step 5:** Run the macOS job once with `WRITE_SWIFT_VECTORS=1`, commit the `swift-sealed.json` it produces as a workflow artifact, and run the Go test. Expected: PASS.
- [ ] **Step 6: Commit** `test(protocol): Swift verifies shared vectors; Go opens Swift-sealed values`.

### Task 6: CI security gates

**Files:**
- Create: `.github/workflows/security.yml`, `.gitleaks.toml`
- Create: `scripts/check-go-licenses.sh`, `scripts/check-swift-licenses.py`, `scripts/test_check_swift_licenses.py`
- Create: `docs/security/license-allowlist.md`
- Create: `.github/workflows/mutation.yml`

**Values:**
- **License allowlist** (SPDX): Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib, 0BSD, Unlicense, MPL-2.0 (file-level copyleft, allowed only for unmodified use; the owner confirms per case). It denies GPL-*, AGPL-*, LGPL-* (App Store static linking), SSPL, BUSL, and unknown licenses.
- **Go:** `go run github.com/google/go-licenses/v2@v2.0.1 check ./... --allowed_licenses=<list>`, from `server/`.
- **Swift:** `check-swift-licenses.py` reads every `Package.resolved` under `apps/apple/` and fails if a package identity is missing from the `swift-packages` table in `license-allowlist.md` (columns `identity | version | SPDX | approved-by`). It does not fetch licenses itself; the table is the owner's record.
- **Secrets:** the `gitleaks` v8.28.0 CLI binary, with its checksum verified: `gitleaks git --redact --config .gitleaks.toml`. The config extends the defaults, and adds rules for PEM private keys, `.p8` content, and the `APP_TURN_SECRET=`/`APP_SMTP_URL=smtp://user:pass` patterns. It allowlists `docs/protocol/test-vectors/` (published test keys) and `.env.example`.
- **Mutation testing:** in `mutation.yml`, weekly plus `workflow_dispatch`, `go run github.com/go-gremlins/gremlins/cmd/gremlins@v0.5.0 unleash` over `./internal/pushproto ./internal/delivery ./internal/identity ./internal/calls`. It uploads the report and sets no threshold yet; this is information only. Pin the version after checking that it exists (`go list -m -versions github.com/go-gremlins/gremlins`), and record the version you actually pinned.

Steps:
- [ ] **Step 1: Failing tests** in `test_check_swift_licenses.py`:
  - `test_unlisted_package_fails`
  - `test_version_mismatch_fails`
  - `test_listed_package_passes`
  - `test_no_resolved_files_passes`, the state before plans 3, 4 and 7 add packages
- [ ] **Step 2:** `python3 -m unittest scripts/test_check_swift_licenses.py`. Expected: FAIL.
- [ ] **Step 3:** Implement the script, and write `license-allowlist.md` with the Go module table:
  - the three plan 2 modules, `approved-by: owner (plan 2 §2)`
  - the planned Swift rows for WebRTC, mls-rs and RNNoise, marked `pending`
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `scripts/check-go-licenses.sh`. Expected: exit 0 (after plan 2 F2 adds pgx).
- [ ] **Step 5:** Install gitleaks locally to `/tmp/claude-1000/…/scratchpad` or `~/.local/bin`, from the release tarball, with its checksum verified. Run `gitleaks git --redact --config .gitleaks.toml` on the repo. Expected: no leaks. If it finds anything, stop and tell the owner; never print the matched value.
- [ ] **Step 6:** Write `security.yml`. On PRs and pushes, it runs the jobs `gitleaks`, `go-licenses`, `swift-licenses` and `govulncheck` (the last one already runs in `server.yml`, so don't duplicate it). Then write `mutation.yml`.
- [ ] **Step 7: Commit.** `ci: secret scanning, license allowlists, weekly mutation testing`.

### Task 7: Operator documents route and security.txt

**Files:**
- Modify: `server/internal/serverinfo/module.go`, adding a route
- Modify: `server/internal/config/config.go` (`DocsDir string`, env `APP_DOCS_DIR`, default empty)
- Create: `deploy/docs/security.txt.example`, `docs/security/disclosure-policy.md`, `SECURITY.md`
- Test: `server/internal/serverinfo/docs_test.go`

**Interfaces — Produces:**

| Route | File in `APP_DOCS_DIR` | Content type |
|---|---|---|
| `GET /.well-known/security.txt` | `security.txt` | `text/plain; charset=utf-8` |
| `GET /legal/privacy` | `privacy.html` | `text/html; charset=utf-8` |
| `GET /legal/terms` | `terms.html` | `text/html; charset=utf-8` |

A missing directory or file gives 404 `not_found`. Files are read on each request through `http.ServeFile`, with no caching, so operators can edit them live. A filename never comes from the request.

`security.txt.example` (RFC 9116): `Contact: mailto:security@example.org`, `Expires: <ISO date less than a year ahead>`, `Policy: https://<host>/legal/disclosure`, `Preferred-Languages: en`, `Canonical: https://<host>/.well-known/security.txt`. `Expires` is required and must not repeat ([RFC 9116 §2.5.5](https://www.rfc-editor.org/rfc/rfc9116#section-2.5.5)). Add a fourth route, `GET /legal/disclosure`, serving `disclosure.html`.

`disclosure-policy.md`, the project-wide policy, has these sections:
- scope: the server, the gateway, the apps, and the protocol
- how to report: email, plus GitHub private vulnerability reporting once the repo exists
- response times: acknowledge within 3 business days, triage within 10, coordinated disclosure within 90 days
- safe harbor
- out of scope: self-hosted servers run by others, rate-limit tests, and social engineering
- credit

`SECURITY.md` links to it.

- [ ] **Step 1: Failing tests:**
  - `TestDocsServed`: with a temp dir holding `security.txt`, the response is 200 with matching bytes and the right content type.
  - `TestDocsMissing`: an empty `DocsDir` → 404 `not_found`, and so does a dir without the file. Never 500.
  - `TestDocsNoTraversal`: `GET /legal/..%2f..%2fetc%2fpasswd` → 404, and only the fixed names are ever opened.
  - `TestSecurityTxtExampleValid`: parse `deploy/docs/security.txt.example`. It has exactly one `Contact` and exactly one `Expires`, and `Expires` parses as RFC 3339.
- [ ] **Step 2:** `go test ./internal/serverinfo/ -run Docs`. Expected: FAIL.
- [ ] **Step 3:** Implement the route and write the documents.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `scripts/check-docs.sh docs/security SECURITY.md`. Expected: exit 0.
- [ ] **Step 5: Commit.** `feat(serverinfo): serve operator security.txt, privacy, terms, disclosure`. Also add `APP_DOCS_DIR` to `deploy/.env.example` and to the admin guide.

### Task 8: Privacy policy and terms (drafts)

**Files:** Create `docs/legal/privacy-policy.md` and `docs/legal/terms.md`, plus `deploy/docs/privacy.html` and `terms.html`, rendered by `scripts/render-legal.py`, which uses the standard library for Markdown headings, lists, paragraphs and links only. Test: `scripts/test_render_legal.py`.

**Required sections, `privacy-policy.md`:** each section starts with the lawyer-review banner (Global Constraints).
1. Who runs this server (operator name, contact, and EU representative if needed — 👤 owner/lawyer).
2. What we collect, mapped one-to-one to server design §9 and the server's tables:
   - account data (username, email, display name)
   - device data
   - conversation membership
   - message timing and size
   - call timing
   - block lists and reports
   - attachments, which are encrypted and expire after `retention.attachmentDays`
   - escrow copies, only in server-readable chats
   - IP addresses: kept in no logs (server design §8), but seen transiently by the server, TURN relays and the gateway
3. What we cannot see in end-to-end encrypted chats, in plain words. Server-readable chats are marked on the chat's info page.
4. Purposes and legal bases (GDPR Art. 6), as a table.
5. Who else sees data:
   - Apple (APNs: token and push timing)
   - the project push gateway
   - TURN relay operators
   - optional Cloudflare TURN, if the admin enables it (GOAL §6)
   - email delivery provider
6. Retention: the policy file values (inbox 30 days, attachments 30 days, escrow 365 days), plus "accounts until you delete them".
7. Your rights: access, deletion (in-app account deletion, immediate, under Guideline 5.1.1(v)), and complaints.
8. Children: minimum age is TBD by the lawyer (13 or 16 per country).
9. Changes and contact.

**Required sections, `terms.md`:**
- the invite-only beta, provided as-is
- acceptable use, matching Guideline 1.2: no harassment or illegal content, with a reporting route
- blocking and reporting; the operator can remove accounts
- account deletion
- no warranty, liability, governing law (TBD), and changes

- [ ] **Step 1: Failing tests:**
  - `test_renders_headings_lists_links`
  - `test_escapes_html`: `<script>` in the Markdown is escaped
  - `test_banner_present`: the render fails if the lawyer-review banner is missing
- [ ] **Step 2:** `python3 -m unittest scripts/test_render_legal.py`. Expected: FAIL.
- [ ] **Step 3:** Implement the renderer and write both documents.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `python3 scripts/render-legal.py`, which writes the two HTML files, and `scripts/check-docs.sh docs/legal`. Expected: exit 0.
- [ ] **Step 5:** Add a §9 consistency check to `scripts/check-docs.sh`: every bullet of server design §9 must appear (case-insensitive) in `privacy-policy.md` §2. Make one deliberate edit to see it fail, then revert it.
- [ ] **Step 6: Commit.** `docs(legal): draft privacy policy and terms for the invite-only beta`. 👤 The owner sends them to a lawyer before the beta server opens.

### Task 9: Export compliance

**Files:**
- Create: `docs/legal/export-compliance.md`
- Modify (after plan 1 merges): `apps/apple/project.yml`, adding `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption: YES` to both app targets and both NSE targets once they exist

**Required sections** (it is a memo and checklist):
1. **What the apps contain:**
   - MLS through mls-rs (AES-128-GCM, X25519, Ed25519, SHA-256)
   - DTLS-SRTP inside the project's own WebRTC build (BoringSSL)
   - HPKE through CryptoKit (OS-provided)
   - TLS through URLSession (OS-provided)

   Every item uses standard algorithms; nothing is proprietary.
2. **Apple:**
   - Set `ITSAppUsesNonExemptEncryption = YES`, because the apps ship encryption beyond what the OS provides.
   - In App Store Connect's export compliance questions, answer that the app uses standard algorithms not provided by Apple's OS.
   - Per Apple, this calls for **the French encryption declaration**, and only if the app is distributed in France. A proprietary algorithm would also need a CCATS, which does not apply here ([Apple: export compliance documentation](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/); [overview](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)).
   - After Apple approves the documents, add the `ITSEncryptionExportComplianceCode` Apple issues to `project.yml`. 👤
3. **United States (EAR):**
   - Working classification: 5D992.c, mass market, under License Exception ENC §740.17(b)(1).
   - The 2021 rule (effective 2021-03-29) removed the §742.15(b) email notification for publicly available encryption source code. It also narrowed the annual self-classification report, which is due February 1 for the prior calendar year, as a CSV, unless a CCATS exists ([BIS annual self-classification](https://www.bis.gov/learn-support/encryption-controls/annual-self-classification); [Thompson Hine summary](https://www.thompsonhine.com/publications/bis-eliminates-most-mass-market-encryption-reporting-obligations); [Baker McKenzie](https://sanctionsnews.bakermckenzie.com/bis-updates-reporting-requirements-relating-to-mass-market-encryption-items-and-publicly-available-software-and-also-updates-certain-classifications/)).
   - **Questions for the lawyer:**
     - does a published Apache-2.0 source tree plus an App Store binary fall under "publicly available" (§734.3(b)(3)) for the source while the binary is mass market?
     - does any annual report apply to the binary?
     - is the open-source origin of the owner's own build relevant?

   Verify against the current eCFR text before filing.
4. **France:** the ANSSI declaration (déclaration de fourniture d'un moyen de cryptologie), the documents ANSSI expects, its timelines, and that the approval is uploaded to App Store Connect. Developers report little guidance, so budget time for it ([Cryptomator's experience](https://cryptomator.org/blog/2016/06/16/indepth-french-app-store/)). Fallback: exclude France from the beta's availability until the declaration is approved. 👤
5. **Other countries:** a checklist for the lawyer covering countries with import rules on encryption or VoIP apps (GOAL §6 also flags China and CallKit). The default is to limit beta availability to countries the lawyer clears.
6. **Record:** the date, the answers given in App Store Connect, the documents uploaded, and the reviewer's name. 👤

- [ ] **Step 1:** Write the memo, with every factual claim followed by a source link.
- [ ] **Step 2:** `scripts/check-docs.sh docs/legal`. Expected: exit 0.
- [ ] **Step 3 (after plan 1 merges):** Add the Info.plist key. The CI build of both app targets must stay green: `gh run watch`, then `gh run view --log-failed`. Expected: green. Note that this removes plan 1's known TestFlight blocker.
- [ ] **Step 4: Commit.** `docs(legal): export compliance memo; declare non-exempt encryption`.

### Task 10: App Store readiness

**Files:**
- Create: `docs/release/app-store-checklist.md`
- Create: `scripts/check-release-config.py`, `scripts/test_check_release_config.py`
- Create: `docs/release/review-notes-template.md`

**`check-release-config.py`** loads `apps/apple/project.yml` as YAML (PyYAML is not in the standard library, so parse it with `ruby -ryaml`, which ships on macOS runners) and every `*.entitlements` file with `plistlib`. It reports each requirement as `PASS` or `MISSING` and exits 1 if anything is missing. With `--allow-missing`, it exits 0 (used before plans 6–8 land). Requirements:

| Target | Requirement |
|---|---|
| iOS app | `aps-environment`; `UIBackgroundModes` containing `voip`, `remote-notification` and `audio`; `com.apple.developer.associated-domains` with `applinks:` and `webcredentials:`; `com.apple.security.application-groups` containing `group.org.example.app`; `keychain-access-groups`; `ITSAppUsesNonExemptEncryption`; an `NSMicrophoneUsageDescription` and an `NSCameraUsageDescription` that aren't empty |
| iOS NSE | an app group and keychain group matching the app's; `com.apple.developer.usernotifications.filtering` (only after Apple grants it — report `PENDING-OWNER` while `--filtering-pending` is passed) |
| Mac app | `com.apple.developer.aps-environment`; app sandbox; network client; device audio input and camera; the app group; the keychain group; associated domains |
| Mac NSE | the app group and the keychain group |
| All | a real bundle ID: report `MISSING` while it is still `org.example.app`, unless `--placeholder-brand-ok` is passed; an app icon set that isn't empty |

- [ ] **Step 1: Failing tests** with fixture projects in temp dirs:
  - `test_complete_fixture_passes`
  - `test_commented_key_still_missing`: the key exists only in a YAML comment, so it must report `MISSING`
  - `test_missing_voip_mode`
  - `test_placeholder_brand_flagged`
  - `test_allow_missing_exit_zero`
- [ ] **Step 2:** `python3 -m unittest scripts/test_check_release_config.py`. Expected: FAIL.
- [ ] **Step 3:** Implement the checker. The tests run on Linux as well, using a fixture-only YAML path when Ruby is absent: fixtures use JSON-compatible YAML, so the script falls back to `json`.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run the script on the real project with `--allow-missing --placeholder-brand-ok`. Expected: a report listing what plans 6–8 must add.
- [ ] **Step 5: Write `app-store-checklist.md`** with these sections, each item a checkbox with its owner (agent or 👤):
  - **Account:** Apple Developer Program enrolled **as an organization** (D-U-N-S number, legal entity) before App Store submission (GOAL §17; Guideline 5.1.1(ix)). 👤
  - **Entitlements and capabilities:** the table above. The **notification-filtering entitlement** (`com.apple.developer.usernotifications.filtering`) is requested from Apple with a justification text, drafted here: "E2EE messenger; the NSE must silently drop duplicate pushes and pushes for removed devices." 👤 submits it. CallKit/PushKit rule: every VoIP push is reported to CallKit (plan 8).
  - **Guideline 1.2 (user-generated content):**
    - a method for filtering objectionable material: message requests from strangers (plan 1 UI) and blocking
    - reporting with timely responses: in-app report (plan 2 E2), the abuse contact, and an operator response target of 24 h
    - the ability to block abusive users: plan 2 E2 and plan 1 UI
    - published contact information: the server info document and the privacy policy

    Cite the [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) §1.2.
  - **Guideline 5.1.1(v):** in-app account deletion (plan 2 A4, `DELETE /v1/me`; plan 6 UI). It is immediate, deletes user content, and self-hosters cannot disable it ([Apple: offering account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/)).
  - **Privacy nutrition labels**, the App Store Connect "App Privacy" answers, derived from privacy policy §2:
    - Contact Info → Email Address (App Functionality; linked to identity; not used for tracking)
    - User Content → messages, photos and audio: *not collected* in E2EE chats, because Apple's definition covers data the developer can access. **Server-readable mode makes this "collected" for the operator.** 👤 Lawyer decision; record it.
    - Identifiers → User ID
    - Diagnostics → none until crash reporting is chosen
  - **Brand and assets:** app name, icon (1024 px), screenshots, and a privacy policy URL (`https://<host>/legal/privacy`). All wait on the brand. 👤
  - **TestFlight:** internal testing first. External testing needs Beta App Review, so prepare review notes from `review-notes-template.md`:
    - a reviewer server URL and two pre-made demo accounts (owner-created; the credentials are entered in App Store Connect, never in the repo)
    - how to receive an invite
    - how to make a call between the two accounts
    - why VoIP push is used
    - encryption export answers (Task 9)
  - **Export compliance:** link to Task 9.
- [ ] **Step 6:** `scripts/check-docs.sh docs/release`. Expected: exit 0.
- [ ] **Step 7: Commit.** `docs(release): App Store checklist and release-config checker`. Add a non-blocking `check-release-config` step (`--allow-missing --placeholder-brand-ok --filtering-pending`) to `apple.yml`.

### Task 11: Beta server operations

**Files:**
- Create: `deploy/Caddyfile`, `deploy/relay/compose.yaml`, `deploy/relay/turnserver.conf`, `deploy/pushgw/compose.yaml`, `deploy/pushgw/.env.example`
- Create: `deploy/scripts/backup.sh`, `deploy/scripts/restore.sh`, `deploy/scripts/restore-drill.sh`
- Create: `.github/workflows/restore-drill.yml`
- Create: `docs/ops/beta-runbook.md`

**Values:**
- **Caddy:** `{$APP_HOST}` reverse-proxies to `appserver:8080`. Caddy's `reverse_proxy` already handles WebSocket upgrades. It adds the header `Strict-Transport-Security "max-age=31536000"`, sends access logs nowhere (no IP logging, server design §8), and uses automatic HTTPS. The app compose sets `APP_TRUST_PROXY=true`.
- **Relay node compose:** coturn 4.18 alone, `network_mode: host`, with the config from plan 2 E5 (deny-lists, TLS on 443, `use-auth-secret`) plus `external-ip` from env and `total-quota` and `user-quota` limits.
- **Push gateway compose:** `postgres:18` plus `pushgw` behind Caddy. The `.p8` file and HPKE key are mounted read-only from `/etc/pushgw/keys` (👤 the owner places them).
- **`backup.sh`:** `pg_dump --format=custom` of the app DB, plus a `tar` of `APP_DATA_DIR`, into one dated directory. Optionally encrypt with `age` (MIT) if the owner approves that tool; otherwise rely on encrypted storage at the backup target. Retention: 14 daily and 8 weekly.
- **`restore.sh`:** into an empty stack, run `pg_restore --clean --if-exists` plus extract the data dir, then `appserver migrate`.

- [ ] **Step 1: Write the failing drill.** `restore-drill.sh` does the following:
  1. `docker compose up` the app stack, with test env and `requiresInvite:false`.
  2. Through the API, create an account via the admin CLI and test helpers, plus one attachment.
  3. Insert a test inbox row with `psql`.
  4. Run `backup.sh`.
  5. `docker compose down -v`.
  6. Bring up a fresh stack and run `restore.sh`.
  7. Check that the account row exists, the inbox row exists, and `GET /v1/attachments/{id}` returns the same bytes (sha256).

  It prints `RESTORE DRILL OK`.
- [ ] **Step 2:** `sh deploy/scripts/restore-drill.sh`. Expected: FAIL (the scripts don't exist).
- [ ] **Step 3:** Write the scripts and the compose files.
- [ ] **Step 4:** Run the drill. Expected: `RESTORE DRILL OK`. Then run `docker compose -f deploy/relay/compose.yaml config -q` and `docker compose -f deploy/pushgw/compose.yaml config -q`. Expected: exit 0.
- [ ] **Step 5:** Add `restore-drill.yml`, which runs weekly and on changes to `deploy/**`.
- [ ] **Step 6: Write `beta-runbook.md`**, all steps 👤 owner-run, each with a verification command:
  1. **Machines:** the app VM (2 vCPU, 4 GB), two relay VMs in two regions, and the gateway VM. Use providers with included traffic (research report §connections).
  2. **DNS:** `chat.`, `turn1.`, `turn2.` and `push.` A and AAAA records. Check with `dig +short`.
  3. **Firewall:**
     - app: 80 and 443
     - relays: UDP and TCP 3478, TCP 443, and the UDP relay range `49152–65535`
     - gateway: 443
     - SSH by key only
  4. **TLS:** Caddy is automatic on the app and gateway. coturn certificates for `turns:` on 443 come from certbot on the relay host (renewal hook restarts coturn).
  5. **Secrets:** create them on the machines only, with `umask 077`:
     - the TURN secret (`openssl rand -base64 32`)
     - the push key (`appserver keygen push`), registered with `appserver push register`
     - the escrow key, only if the policy allows server-readable chats
     - the APNs `.p8` on the gateway only
  6. **First start:** `docker compose up -d`; `curl https://chat.<domain>/v1/server`; `appserver invite create --uses 5`.
  7. **Relay checks:** run a [Trickle ICE](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/) test with credentials from `GET /v1/turn`. Expected: `relay` candidates from both relays. Also check that a relay to a private address fails (the deny-list).
  8. **Push check:** a TestFlight build registers, and a message to a backgrounded phone shows a notification.
  9. **Backups:** a daily cron for `backup.sh`, an off-site copy, and **a restore drill on a scratch VM before inviting anyone, then monthly**. Record the date and result.
  10. **Monitoring:**
      - an external HTTPS probe on `/v1/server` and on the gateway's `/v1/gateway-key`
      - disk and memory alerts
      - weekly review of `/debug/vars` through an SSH tunnel to the admin port

      The tool is the owner's choice and needs approval if self-hosted.
  11. **Updates:** a monthly OS patch window, and image updates for coturn security releases (GOAL §6). Follow the upgrade and rollback sections of the admin guide (plan 2 E5).
  12. **Incident basics:** rotate the TURN secret, rotate the push key (register again), revoke a compromised device (`DELETE /v1/devices/{id}`), and contact the security reporter (disclosure policy).
- [ ] **Step 7:** `scripts/check-docs.sh docs/ops`. Expected: exit 0.
- [ ] **Step 8: Commit.** `ops: beta deployment files, backup/restore with CI drill, runbook`.

### Task 12: Independent security review

**Files:** Create `docs/security/audit-scope.md` and `docs/security/otf-application.md`.

**Required content:**
- **`audit-scope.md`:**
  - in scope, by priority:
    1. MLS usage in the clients (plan 4 wrapper, plan 7 flows)
    2. the NSE and app-group key storage
    3. identity: passkeys, email codes, sessions, linking (plan 2 lane A)
    4. the delivery service's epoch ordering and escrow (lane B)
    5. the push gateway, sealed handles and signatures (lane D)
    6. call signaling and fingerprint binding (lane C, plan 8)
    7. the WebRTC/Opus build pipeline and patch set (plan 3)
  - out of scope: UI and the sample data
  - artifacts given to the auditor: this threat model, `docs/protocol/`, the test vectors, and the commit hash
  - success criteria: no open Critical or High findings before the public beta (GOAL §13)
  - budget fallback if OTF declines: about $40k–80k (GOAL §5)
- **`otf-application.md`:** the answers drafted for OTF's Security Lab (formerly Red Team Lab) online form:
  - a project summary
  - internet-freedom relevance: a self-hostable E2EE messenger with low-data calls for weak networks
  - users and regions
  - the team's ability to respond to and maintain findings
  - the requested service: software security audit plus architecture review
  - an acknowledgment that findings are published after a disclosure period

  OTF's lab supports audits for projects relevant to internet freedom that it does not fund, and publishes the results after responsible disclosure ([OTF Security Lab](https://www.opentech.fund/labs/security-lab/); [OTF guide](https://guide.opentech.fund/our-labs/red-team-lab)). 👤 The owner submits at [apply.opentech.fund](https://apply.opentech.fund/) once plans 7 and 8 are feature-complete.

- [ ] **Step 1:** Write both documents.
- [ ] **Step 2:** `scripts/check-docs.sh docs/security`. Expected: exit 0.
- [ ] **Step 3: Commit.** `docs(security): audit scope and OTF Security Lab application draft`.

### Task 13: Code owners and branch protection

**Files:**
- Modify: `.github/CODEOWNERS` (created by plan 2 F1)
- Create: `scripts/setup-branch-protection.sh`, `docs/ops/repo-setup.md`

**Values:**
- **CODEOWNERS additions,** owned by `@velixio`:
  - `/apps/apple/Packages/SecureMessaging/`, `/apps/apple/Packages/WebRTCBinary/`
  - `/apps/apple/Packages/ServerKit/Sources/ServerKit/Auth/`, `/apps/apple/Packages/ServerKit/Sources/ServerKit/Push/`
  - `/apps/apple/NotificationService/`
  - the plan 8 call engine path (`/apps/apple/Packages/*/Sources/**/Calls/Live*` until plan 8 fixes it)
  - `/docs/protocol/`, `/docs/security/`, `/deploy/`
  - `/.github/workflows/`, `/.github/CODEOWNERS`
- **The `main` ruleset,** applied with `gh api` against `repos/{owner}/{repo}/rulesets`:
  - pull request required, with 1 approval and code-owner review required
  - stale approvals dismissed on push
  - required status checks: `server`, `apple`, `docs`, `security`
  - no force push, no deletion, linear history
  - bypass list empty, or holding only the owner if decision 2 picks bypass
- **Machine user (decision 2):** `repo-setup.md` tells the owner how to:
  - create the bot account
  - invite it with `write` access
  - create a fine-grained PAT scoped to this repo (contents, pull requests), expiring in 90 days
  - give it to the agent runtime as `GH_TOKEN`
- Also enable private vulnerability reporting, secret scanning and push protection: `gh api -X PATCH repos/{o}/{r}` with `security_and_analysis`.

- [ ] **Step 1: Write `setup-branch-protection.sh`.** It is idempotent: it looks up an existing ruleset by name (`main-protection`) and updates it. Its `--dry-run` flag prints the JSON without calling the API.
- [ ] **Step 2:** `sh scripts/setup-branch-protection.sh --dry-run | python3 -m json.tool`. Expected: valid JSON containing the four required checks.
- [ ] **Step 3:** Write `repo-setup.md` and update CODEOWNERS.
- [ ] **Step 4: Commit.** `ops: code owners for client crypto paths, branch protection script`.
- [ ] **Step 5 👤:** The owner creates the GitHub repo and runs the script without `--dry-run`. Then check `gh api repos/{o}/{r}/rulesets`. Expected: `main-protection` is active.

### Task 14: Beta gate

**Files:** Create `docs/release/beta-gate.md` and `scripts/check-beta-gate.py`. Test: `scripts/test_check_beta_gate.py`.

`beta-gate.md` is a table, `Item | Source | Evidence | Status`. It holds every bullet of GOAL §11 Phase 1 (deliverables and exit criteria) plus the §13 items relevant to a *private* beta. Evidence must be a link to:
- a green workflow run URL
- a measurement report (plan 5)
- a document in this repo
- a signed-off owner line (`👤 <date> <initials>`)

Status is `done`, `open` or `waived (<reason>, owner <date>)`.

The checker fails when:
- a row is `done` with empty evidence
- `docs/protocol/README.md` still contains `TODO(plan 7)`
- the threat model's review log has no owner line
- a legal document has no "lawyer reviewed <date>" line under its banner (it reports `open`; it doesn't hide it)

- [ ] **Step 1: Failing tests:**
  - `test_done_without_evidence_fails`
  - `test_waived_requires_reason`
  - `test_todo_in_protocol_fails`
  - `test_all_open_reports_but_exit_zero_with_report_flag`
- [ ] **Step 2:** `python3 -m unittest scripts/test_check_beta_gate.py`. Expected: FAIL.
- [ ] **Step 3:** Implement the checker and write `beta-gate.md` with every row `open`.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `python3 scripts/check-beta-gate.py --report`. Expected: a list of open items and exit 0.
- [ ] **Step 5: Commit.** `docs(release): beta gate with evidence checker`.
- [ ] **Step 6:** Do the final review (superpowers:requesting-code-review, on Opus) over this plan's commits and Review Focus. 👤 The owner opens the beta only when `check-beta-gate.py` (without `--report`) exits 0.

## Not in this plan

These are owned elsewhere or deferred:
- crash reporting and analytics tool choice (owner)
- Android, Windows and Linux store requirements (Phase 3)
- DSA, UK Online Safety Act and India IT Rules duties for a *public* service. GOAL §17 keeps the beta invite-only; public sign-up needs a separate legal plan.
- phone-number discovery and SMS costs (Phase 2)

## Sources

- Apple: [export compliance documentation](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/), [overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance), [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), [offering account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/), [notification filtering entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.usernotifications.filtering)
- US BIS: [encryption controls](https://www.bis.gov/learn-support/encryption-controls), [annual self-classification](https://www.bis.gov/learn-support/encryption-controls/annual-self-classification); summaries of the 2021 rule: [Thompson Hine](https://www.thompsonhine.com/publications/bis-eliminates-most-mass-market-encryption-reporting-obligations), [Baker McKenzie](https://sanctionsnews.bakermckenzie.com/bis-updates-reporting-requirements-relating-to-mass-market-encryption-items-and-publicly-available-software-and-also-updates-certain-classifications/), [DLA Piper](https://www.dlapiper.com/en/insights/publications/2021/04/latest-regulatory-changes-reduce-burden-for-software-and-technology-companies)
- France: [Cryptomator on the French App Store declaration](https://cryptomator.org/blog/2016/06/16/indepth-french-app-store/)
- [RFC 9116 (security.txt)](https://www.rfc-editor.org/rfc/rfc9116)
- OTF: [Security Lab](https://www.opentech.fund/labs/security-lab/), [guide: Red Team Lab](https://guide.opentech.fund/our-labs/red-team-lab), [Whonix forum on applying](https://forums.whonix.org/t/red-team-lab-security-audit/13593)
