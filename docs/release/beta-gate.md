# Private beta gate

The owner opens the invite-only private beta only when `python3 scripts/check-beta-gate.py` (without `--report`) exits 0. CI runs it with `--report`, which prints every open item and still exits 0, so Phase 1 work in progress keeps CI green.

Each row is one item from [GOAL.md](../../GOAL.md) section 11 (Phase 1 deliverables and exit criteria) or section 13 (Definition of Done, the items relevant to a private beta; localization and wider-public-beta wording are left out). The last four rows are release-process checks; the script also verifies the first three on its own.

**Evidence** is one of: a link to a green workflow run, a link to a measurement report under [docs/measurements](../measurements/README.md), a link to a document in this repository, or a signed-off owner line (`👤 <date> <initials>`). Only the owner writes an owner line. **Status** is `done` (evidence required), `open`, or `waived (<reason>, owner <date>)`. Waivers come only from the [owner decisions log](../superpowers/plans/README.md).

| Item | Source | Evidence | Status |
|---|---|---|---|
| Native iPhone (iOS 17+) and macOS clients | GOAL §11 Phase 1 deliverable |  | open |
| Go server and documented self-hosted deployment | GOAL §11 Phase 1 deliverable | [server workflow, green on main](https://github.com/drag0n-flyer/myApp/actions/runs/40580743296); [admin guide](../server/admin-guide.md); [beta runbook](../ops/beta-runbook.md) | done |
| Project push gateway | GOAL §11 Phase 1 deliverable |  | open |
| Account/device identity and secure authentication | GOAL §11 Phase 1 deliverable |  | open |
| Multi-device linking and revocation (iPhone and Mac) | GOAL §11 Phase 1 deliverable |  | open |
| Strict E2EE one-to-one messaging over MLS, no server decryption path | GOAL §11 Phase 1 deliverable |  | open |
| Server-readable mode, per-conversation policy, encryption type on chat info page, no silent downgrade | GOAL §11 Phase 1 deliverable |  | open |
| Encrypted attachment transfer | GOAL §11 Phase 1 deliverable |  | open |
| One-to-one audio and video calls, no media server | GOAL §11 Phase 1 deliverable |  | open |
| User-configurable home server; invite-only project-operated beta server | GOAL §11 Phase 1 deliverable |  | open |
| Relay discovery and health-aware selection across at least two TURN relay nodes | GOAL §11 Phase 1 deliverable |  | open |
| Low-data modes, noise suppression, call diagnostics | GOAL §11 Phase 1 deliverable |  | open |
| Offline synchronization, push notifications, call history | GOAL §11 Phase 1 deliverable |  | open |
| Basic blocking, reporting, abuse controls, in-app account deletion | GOAL §11 Phase 1 deliverable |  | open |
| Documented installation | GOAL §11 Phase 1 exit criterion |  | open |
| Reliable messaging | GOAL §11 Phase 1 exit criterion |  | open |
| Tested low-data calls | GOAL §11 Phase 1 exit criterion |  | open |
| iPhone and macOS apps securely exchange messages and attachments | GOAL §13 (private beta) |  | open |
| Users choose the project-operated server or configure an independent server | GOAL §13 (private beta) |  | open |
| Server discovery and identity verification documented and tested | GOAL §13 (private beta) |  | open |
| One-to-one audio/video calls work across realistic network conditions | GOAL §13 (private beta) |  | open |
| Relay selection tested with multiple nodes, no unnecessary media hops | GOAL §13 (private beta) |  | open |
| Low-data modes have published, repeatable measurements | GOAL §13 (private beta) |  | open |
| Noise suppression and echo cancellation evaluated against representative noise samples | GOAL §13 (private beta) |  | open |
| Battery and resource usage measured on physical devices | GOAL §13 (private beta) |  | waived (device measurements (call lab L10) deferred, automated test calls on Mac runners replace them for now, owner 2026-10-28) |
| Strict E2EE and server-readable modes distinguished on each chat's info page and in the documentation | GOAL §13 (private beta) |  | open |
| Strict E2EE has no server-side decryption or administrator override path; downgrade behavior tested | GOAL §13 (private beta) |  | open |
| Encryption and key lifecycle behavior have undergone security review (independent review) | GOAL §13 (private beta) |  | waived (independent security review submission set aside for now, owner 2026-10-28) |
| A third party can deploy, upgrade, back up and restore the server using the documentation | GOAL §13 (private beta) |  | open |
| Failure modes, privacy limitations and administrator-visible metadata documented | GOAL §13 (private beta) |  | open |
| Accessibility, crash reporting and abuse controls in place | GOAL §13 (private beta) |  | open |
| App Store and legal items (GOAL §17) complete | GOAL §13 (private beta) |  | open |
| No major security or reliability issue remains unresolved | GOAL §13 (private beta) |  | open |
| Threat model review log has an owner sign-off line | docs/security/threat-model.md section 9 |  | open |
| Protocol README has no TODO(plan 7) | docs/protocol/README.md |  | open |
| Each legal document has a 'lawyer reviewed <date>' line under its banner | docs/legal/ |  | open |
| Owner review of sensitive code (identity, delivery, calls, push, pushgw, auth, crypto) before opening the beta | Plans README decision log |  | waived (end-of-Phase-1 owner review set aside for now, owner 2026-10-28) |
