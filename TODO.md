# Phase 1 progress

Last updated: 2026-10-28 (afternoon).

**Overall: 89 of 126 tasks done (71%).** About 10 agents work in parallel, and Mac CI is on.

All Apple code (iPhone and Mac) is written on Linux. A task with that code counts as done here once it is written and reviewed. It is checked for real only when its Mac CI run is green.

ETAs are estimates of agent working time from now, with about 10 agents running at once.

## By plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`; CI green after the Go 1.26 patch fix | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main`; first Mac CI fixes in progress | 0.5 day |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 12 / 14 | 86% | Mac CI fixes in progress (Swift 6 errors in passkeys and the account service) | 0.5 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: WebRTC builds on Mac CI (each build takes hours) | 1 day |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 3 / 6 | 50% | M3: the Rust library built for iPhone/Mac on CI | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 9 / 16 | 56% | K2 (call screen and call notifications) in review; lane M needs the WebRTC build | 1.5 days |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 8 / 16 | 50% | four lanes in parallel: A2 re-review, B1 fixes, D3 building | 1 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 4 / 14 | 29% | Task 4 (test vectors) final fix; Tasks 7 and 8 on `main` | 1 day, plus owner items |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 6 / 11 | 55% | tooling on `main`; lab app (L3, L4) waits for the WebRTC build | 1 day after WebRTC, plus owner device time |

## Waiting on the owner

1. **Approve three Python libraries** for call-quality scoring (call lab L8, decision 4): `numpy`, `onnxruntime`, `speequal`. Also choose the noise recordings (decision 5): MUSAN or the DNS Challenge set.
2. **Voice note limits:** discard notes under 0.5 seconds, stop recording at 10 minutes. The plan didn't set these, so confirm or change them.
3. **Docs Tasks 1 and 6:** approve the document-check and security-gate tools.
4. **Code owners (docs Task 13):** decision 2 is still open.
5. **Revoke the GitHub token** stored in the repo's remote URL, and log in with `gh auth login` as `drag0n-flyer`.
6. **The "owner to confirm" items** in [rulings-2026-10-27.md](docs/superpowers/rulings-2026-10-27.md).
7. **End of Phase 1:** one review of all code touching encryption, sign-in, push and calls, and the call-lab measurements on real devices (L10).

## ETA to the end of Phase 1

- **Agent work:** about 2–3 days with 10 agents. The slowest path is the WebRTC build, then calling lane M and the lab app.
- **After that:** the owner's device measurements (about 9 hours for the full run set) and the final owner review.
