# Progress

Last updated: 2026-10-28 (night).

**iPhone/Mac Phase 1: 116 of 128 tasks done (91%).**

**Android: 4 of 43 tasks done (9%).** The owner moved Android up from Phase 3 and approved its library list. Lanes A–G are now running.

About 12–14 agents work in parallel. Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

App code is written on Linux. It counts as done here once it is written and reviewed. It is checked for real only when its CI run is green.

ETAs are agent working time from now.

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`; deferred review fixes merged; IPv6 rate limits per /64 | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main`, plus privacy manifests and the open-source licenses page | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 13 / 14 | 93% | S3: end-to-end test against the real server on a Mac runner, in progress | 0.5 day |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 5 / 7 | 71% | M6 in progress; M7 in its review fix round | 0.5–1 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: Mac CI build, waiting for a Mac runner; then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | media lane on `main`; I1 left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1: integration with calling plus CI, in progress | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 12 / 14 | 86% | Task 5 (Swift test-vector verifier) open; Task 13 deferred by the owner | 0.5 day |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 9 / 12 | 75% | lab app, Mac rig and scoring on `main`; L10a (automated test calls) waits for WebRTC; L10 deferred; L11 last | 1 day after WebRTC |

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md), [design](docs/superpowers/specs/2026-10-28-android-client-design.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI on sample data | 9 | 0 | A1 (Gradle scaffold) in progress |
| B, networking and accounts | 7 | 1 | B1 merged; B2 onward waits for A1 |
| C, MLS bindings and cross-build | 4 | 1 | C1 (HPKE) merged; C2 (Android build + Kotlin bindings) waits for the toolchain install |
| D, storage and messaging | 7 | 1 | D1 (shared test vectors) merged; D2 onward waits for A1 |
| E, WebRTC Android build and calls | 6 | 0 | E1 (scripts and patches) reviewed and fixed, held until E2's first build; E2 (CI build on Linux) in progress |
| F, push (FCM and UnifiedPush) | 4 | 1 | F1 (FCM in the gateway) merged; F2 (server push kinds, UnifiedPush) in progress |
| G, CI | 4 | 0 | G1 (toolchain install + workflow skeleton) in progress |
| I, integration and iPhone↔Android test | 2 | 0 | waits for all lanes |

ETA: about 3–4 days of agent work.

## Main CI

The Apple apps workflow on `main` is red until the fix on `fix/swift62-ci` lands. That fix moves the Linux build to Swift 6.2 and picks an available simulator. Its full Mac run is in progress.

## Waiting on the owner

1. **Android decisions still open.** Each is listed in the Android plan's "owner to decide":
   - The final app ID. It can't change after the first Play release; the placeholder is `org.example.app`.
   - Whether to support 32-bit phones (`armeabi-v7a`).
   - Whether passkeys work on any server that lists the app in `assetlinks.json`. Today they work only when the operator sets `APP_ANDROID_APPS`.
   - The call payload's `ceiling` field. The spec says it is a string, but the iPhone sends an integer.
   - Whether the Rust MLS state files are encrypted on Android.
   - A `quiet` flag for receipts, edits and deletes, which would save FCM high-priority quota.
   - An opt-in "keep connected" mode for phones without push.
   - Whether to ask for the battery-optimisation exemption or only show a hint.
   - The CPU threshold for hiding enhanced noise suppression, after it is measured on the OnePlus 5.
   - A build without Firebase, for F-Droid.
   - The Play data safety answers and the account-deletion web page that Play requires.
   - A self-hosted runner for automated iPhone↔Android call tests.
2. **Before App Store submission:**
   - app icons
   - the Mac camera permission
   - Apple's notification-filtering permission
   - how crash reports are sent and kept (marked "TO BE FILLED IN" in the privacy policy)
3. **Set aside until the end of Phase 1:**
   - owner review of sensitive code
   - the independent security review
   - on-device measurements (L10)
   - the USB-microphone check of the Mac rig
