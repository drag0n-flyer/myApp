# Independent Security Review: Audit Scope

**Status:** draft for owner review. It goes to the auditor, or to OTF's Security Lab, once plans 7 and 8 are feature-complete.

This document tells an outside reviewer what to examine, in order of priority. It builds on the [threat model](threat-model.md), which lists each threat and the plan task that mitigates it. Plan numbers follow the [roadmap](../superpowers/plans/README.md).

## In scope, by priority

| # | Area | Where it is built | Why it ranks here |
|---|---|---|---|
| 1 | MLS usage in the clients: the wrapper around mls-rs and the flows that call it | [plan 4](../superpowers/plans/2026-10-27-mls-client-core.md), [plan 7](../superpowers/plans/2026-10-27-client-messaging.md) | Message confidentiality rests on it. No public audit of mls-rs exists (OPEN-1). Much of this code was written by AI agents (OPEN-5). |
| 2 | The Notification Service Extension (NSE) and app-group key storage | plan 7 | Keys are shared across processes, and the NSE decrypts under tight memory and time limits. |
| 3 | Identity: passkeys, email codes, sessions, device linking | [plan 2](../superpowers/plans/2026-10-27-go-server.md), lane A | Account takeover and silent device addition (OPEN-7, OPEN-8, OPEN-9, OPEN-24). |
| 4 | The delivery service: epoch ordering and escrow | plan 2, lane B | Group state consistency, withholding (OPEN-19) and escrow binding (OPEN-20). |
| 5 | The push gateway: sealed handles and request signatures | plan 2, lane D | Key custody and the raw-token view (OPEN-13). |
| 6 | Call signaling and fingerprint binding | plan 2, lane C; [plan 8](../superpowers/plans/2026-10-27-client-calling.md) | Media keys must be bound to the authenticated MLS identities. |
| 7 | The WebRTC and Opus build pipeline and patch set | [plan 3](../superpowers/plans/2026-10-27-webrtc-opus-build.md) | Supply chain and patch correctness (OPEN-16, OPEN-23). |

The reviewer should also read the open risks in [section 8 of the threat model](threat-model.md#8-open-risks) and say which they agree are accepted for the beta.

## Out of scope

- The UI and the sample data ([plan 1](../superpowers/plans/2026-10-27-app-ui-sample-data.md)).
- Everything the threat model lists as out of scope for Phase 1, such as federation, group calls, and Android.
- Apple's operating system, APNs, and the cryptography libraries as shipped by their vendors, except for how the project uses them.

## Artifacts given to the auditor

- The [threat model](threat-model.md), at the commit being reviewed.
- The protocol specification in `docs/protocol/`.
- The test vectors in `docs/protocol/test-vectors/`.
- The commit hash of the code under review. The scope applies to that commit only.
- Read access to the repository and build instructions. No production credentials or signing keys are given.

## Success criteria

No open Critical or High finding before the public beta (GOAL §13, Definition of Done). Medium and Low findings each get an owner and a decision: fix, or accept in the threat model's open risks.

## Budget fallback

If OTF declines or cannot schedule the review, the owner budgets about $40k to $80k for a commercial review with the same scope (GOAL §5).
