# Phase 1 progress

Last updated: 2026-10-28 (evening).

**Overall: 106 of 127 tasks done (83%).** The total is now 127 because MLS task M7 was added.

About 10 agents work in parallel. Mac CI is on, and runs only the jobs a change needs.

Apple code (iPhone and Mac) is written on Linux. It counts as done here once it is written and reviewed. It is checked for real only when its Mac CI run is green.

ETAs are agent working time from now.

## By plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main` | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | iPhone app builds and its UI tests pass; Mac app builds, with 1 Mac UI test left to fix | 0.5 day |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 12 / 14 | 86% | Linux tests green; 4 keychain tests need a small test app; Mac end-to-end test | 0.5 day |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 5 / 7 | 71% | M1–M5 done; switching to RustCrypto on all targets (owner decision); then M6 and M7 (member keys, crash-safe decrypt, call-only check) | 1 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: WebRTC builds on Mac CI (hours per build); then Task 6 (first release) | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 12 / 16 | 75% | lanes P, S and K done; media lane M waits for the WebRTC build; then I1 | 1–1.5 days |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | all four lanes done and on `main`; I1 (integration with calling, CI) in progress | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 9 / 14 | 64% | Tasks 2, 3, 4, 7, 8, 9, 10, 11 done, 12 drafted; 1 and 6 need tool approval, 13 an owner decision, 14 comes last | owner items, then 0.5 day |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 6 / 11 | 55% | tooling and the noise bench done; lab app (L3) and Mac rig (L4) wait for WebRTC; scoring waits for approval | 1 day after WebRTC, plus owner device time |

## Waiting on the owner

1. **Approve three Python libraries** for call-quality scoring: `numpy`, `onnxruntime` and `speequal`. Also choose the noise recordings: MUSAN or the DNS Challenge set.
2. **Approve tools:** the document-check and security-gate tools (docs Tasks 1 and 6), and `age` for encrypting backups.
3. **Decide:** code owners and branch protection (docs Task 13).
4. **Confirm the voice-note limits:** notes under 0.5 seconds are discarded, and recording stops at 10 minutes.
5. **Before App Store submission:** app icons, the Mac camera permission, and a privacy manifest. Apple must also grant the notification-filtering permission.
6. **GitHub:** revoke the token stored in the repo's remote URL, and log in with `gh auth login` as `drag0n-flyer`.
7. **The "owner to confirm" items** in [rulings-2026-10-27.md](docs/superpowers/rulings-2026-10-27.md).
8. **End of Phase 1:**
   - Run the call-lab measurements on real devices (about 9 hours).
   - Submit the independent security review (docs Task 12; drafts are ready).
   - Do one review of all code that touches encryption, sign-in, push and calls.

## Owner decisions made today

- The repo moved to `drag0n-flyer/myApp` (public). History was rewritten to the `drag0n-flyer` identity.
- All reviewed work merges to `main`. Owner review of sensitive code waits until the end of Phase 1.
- CI runs only what a change needs.
- MLS uses RustCrypto on iPhone, Mac and Linux. The CryptoKit add-on was dropped because it needs macOS 26.

## ETA to the end of Phase 1

- **Agent work:** about 1–1.5 days. The WebRTC build sets the pace, because calling media and the lab app wait for it.
- **After that:** the owner's device measurements, the security review submission, and the final owner review.
