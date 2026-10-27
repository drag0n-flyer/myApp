# Phase 1 progress

Last updated: 2026-10-28.

**Overall: 74 of 126 tasks done (59%).**

All Apple code (iPhone and Mac) is written on Linux and has never been compiled. A task with that code counts as done here once it is written and reviewed. It is checked for real only after Mac CI runs.

ETAs are estimates of agent working time. They count from when the blocker is cleared, not from today.

## By plan

| Plan | Done | % | Waiting on | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | none (merged to `main`) | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | Mac CI (tasks 7–16 never compiled) | 1 day of fixes after CI |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 12 / 14 | 86% | S2 review after first compile; S3 needs Mac CI | 0.5 day after CI |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Tasks 5–6 need Mac CI | 1 day after CI |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 3 / 6 | 50% | M3, M5, M6 need Mac CI | 1 day after CI |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 8 / 16 | 50% | lanes M and K and I1 need Mac CI and the WebRTC build | 2 days after CI |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 3 / 14 | 21% | Tasks 1 and 6: tool approval. Tasks 4, 5, 7, 10, 11, 13, 14: other plans | 1 day, spread over the phase |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 1 / 16 | 6% | F2 onward: merge MLS Swift and networking branches first | 2 days (can start now) |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 0 / 11 | 0% | L10: owner measures on devices with the audio adapters | 1.5 days of code, plus owner device time |

## What blocks the rest

1. **Mac CI.** Turn on GitHub Actions on `macos-26` runners. The repo is private, so these minutes are billed. Making the repo public makes them free. About 22 tasks wait on this.
2. **Branch merges for messaging.** `feat/mls-swift` and `feat/client-networking` must be merged into `feat/client-messaging`. No owner action needed; ask to start.
3. **Owner decisions still open:**
   - wording for a paused chat
   - approval for the docs tools (Tasks 1 and 6)
   - a bot account for GitHub reviews
   - the "owner to confirm" items in [rulings-2026-10-27.md](docs/superpowers/rulings-2026-10-27.md)
4. **Owner review.** Code touching crypto, auth, push, calls and the WebRTC build gets one review at the end of Phase 1, before release.

## ETA to the end of Phase 1

- **Without Mac CI:** about 4 days of agent work can still be done: messaging, call-lab code, and docs. After that everything stops.
- **With Mac CI on:** about 1 week of agent work after CI starts, including fix rounds for code that has never been compiled. The owner's device measurements (call lab L10) and the final owner review come on top.
