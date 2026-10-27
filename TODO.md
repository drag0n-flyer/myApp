# Progress

Last updated: 2026-10-28 (night).

**iPhone/Mac Phase 1: 115 of 128 tasks done (90%).** The total gained one task: L10a, automated test calls, which replaces the on-device measurements for now.

**Android: 0 of 43 tasks done.** Android started today, after the owner moved it up from Phase 3. 3 tasks are in review and 2 more are being built.

About 10–14 agents work in parallel. Mac CI runs only the jobs a change needs. Android CI will use Linux runners only.

Apple and Android app code is written on Linux. It counts as done here once it is written and reviewed. It is checked for real only when its CI run is green.

ETAs are agent working time from now.

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`; 14 deferred review fixes also merged; IPv6 rate limits keyed per /64 | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main`; plus privacy manifests and an open-source licenses page | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 13 / 14 | 93% | keychain tests now run inside a test app and pass; S3 (end-to-end test against the real server on a Mac runner) in progress | 0.5 day |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 5 / 7 | 71% | RustCrypto on all platforms, on `main`; M6 in progress; M7 (safety-number keys, crash-safe processing, call-only decryption) in its review fix round | 0.5–1 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: WebRTC build on Mac CI (hours per build; waiting for a Mac runner); then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | media lane on `main`: call session, stats and video levels, noise suppression; compiles once WebRTC is released; I1 is left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1 (integration with calling, CI) in progress | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 12 / 14 | 86% | doc checks, security CI gates and the beta checklist on `main`; Task 5 (Swift test-vector verifier) open; Task 13 deferred by the owner | 0.5 day |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 8 / 12 | 67% | lab app (L3) and Mac rig (L4) on `main`; scoring (L8) in its review fix round; L10a (automated test calls) waits for WebRTC; L10 (device runs) deferred; L11 last | 1 day after WebRTC |

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md), [design](docs/superpowers/specs/2026-10-28-android-client-design.md))

| Lane | Tasks | Now | Waits for |
|---|---|---|---|
| A, scaffold and UI on sample data | 9 | not started | the owner's library approval |
| B, networking and accounts | 7 | B1 (server and iPhone accept Android devices) in review | A1 for B2 onward |
| C, MLS bindings and cross-build | 4 | C1 (HPKE functions in the Rust layer) in progress | A1 for C3 and C4 |
| D, storage and messaging | 7 | D1 (shared test vectors) in its review fix round | A1 for D2 onward |
| E, WebRTC Android build and calls | 6 | E1 (build scripts and patches) in progress | A1, E2 |
| F, push (FCM and UnifiedPush) | 4 | F1 (FCM sender in the push gateway) in review | the owner's library approval, A1 |
| G, CI | 4 | not started | A1 |
| I, integration and iPhone↔Android test | 2 | not started | all lanes |

ETA: about 3–4 days of agent work after the library approval. Lane A1 unblocks most of the rest.

## Main CI

The Apple apps workflow on `main` is red. CI built the Linux packages with Swift 6.0, but the app is built with Xcode 26's Swift 6.2, and the lock code didn't compile on 6.0. The macOS 26 runner also has no "iPhone 16" simulator. A fix is on `fix/swift62-ci`, and its full Mac run is in progress.

## Waiting on the owner

1. **Approve the Android library list.** It includes Jetpack Compose, AndroidX, OkHttp, SQLCipher, Firebase Messaging, the UnifiedPush connector, JNA, ZXing, and test tools. The full list is in the Android plan's "Dependencies needing owner approval" section.
2. **Android decisions still open.** The Android plan's "owner to decide" list has 13 open items, including:
   - the app ID, which can't change after the first Play release
   - 32-bit phone support
   - whether passkeys work on any server listed in `assetlinks.json`
   - the call payload's `ceiling` field: the spec says string, the iPhone sends an integer
3. **Before App Store submission:**
   - app icons
   - the Mac camera permission
   - Apple's notification-filtering permission
   - the crash-reporting setup (how reports are sent and kept)
   - listing the open-source licenses page in the review notes
4. **GitHub:** revoke the token in the repo's remote URL when convenient. The owner says the account is a throwaway.
5. **End of Phase 1, set aside for now:**
   - owner review of sensitive code
   - independent security review
   - on-device measurements (L10)
   - USB-microphone check of the Mac rig

## Owner decisions made today

- The repo moved to `drag0n-flyer/myApp` (public). History was rewritten to the `drag0n-flyer` identity, and `drag0n-flyer` is the code owner.
- Reviewed work merges to `main`. Owner and external reviews are set aside for now, and agent reviews continue.
- CI runs only what a change needs. There is no branch protection for now.
- MLS uses RustCrypto on all platforms.
- Voice notes are up to 30 minutes.
- Device measurements are replaced by automated test calls (L10a).
- Approved:
  - the scoring libraries: numpy, onnxruntime and speequal
  - the docs and security CI tools
  - the Unicode-3.0 and MPL-2.0 licenses
  - MUSAN noise clips
- SMTP: the operator fills in `APP_SMTP_URL` and `APP_MAIL_FROM` in `deploy/.env`.
- The privacy manifest and policy declare these, none of them for tracking:
  - email
  - name
  - user ID
  - abuse reports
  - phone number
  - device ID
  - crash data
- Android starts now:
  - Kotlin + Compose
  - Rust MLS through Kotlin bindings
  - Android 10+, with the OnePlus 5 as a reference device
  - full sign-up on Android
  - HPKE from the Rust layer
  - Ogg Opus for voice messages on every platform
