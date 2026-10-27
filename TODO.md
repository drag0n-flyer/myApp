# Progress

Last updated: 2026-10-28 (late night).

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 117 / 128 | 91% |
| Android | 9 / 43 | 21% |
| Admin dashboard | 0 / 15 | 0% (setup task in progress) |

About 15 agents work in parallel. Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

App code is written on Linux. It counts as done here once it is written and reviewed. It is checked for real only when its CI run is green.

ETAs are agent working time from now.

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`, plus Android device support and FCM/UnifiedPush delivery | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main`, plus app icons, privacy manifests and the open-source licenses page | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 13 / 14 | 93% | S3: end-to-end test against the real server, in progress | 0.5 day |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 5 / 7 | 71% | M6 in progress; M7 in its review fix round | 0.5–1 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: Mac CI build, waiting for a Mac runner; then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | media lane on `main`; I1 left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1: integration with calling plus CI, in progress; Ogg Opus voice messages on `main` | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 13 / 14 | 93% | only Task 13 left, deferred by the owner | done for now |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 9 / 12 | 75% | L10a (automated test calls) waits for WebRTC; L10 deferred; L11 last | 1 day after WebRTC |

Queued for after messaging I1: the iPhone/Mac app ID moves into a single file, `apps/apple/AppID.xcconfig`.

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI | 9 | 2 | A1 and A2 on `main`; A3 (sample chats, onboarding) in review |
| B, networking and accounts | 7 | 1 | B1 on `main`; B2 (HTTP client) fixed; B3 (endpoints) in review |
| C, MLS bindings | 4 | 2 | C1 and C2 on `main`; C3 (live engine) in review |
| D, storage and messaging | 7 | 1 | D1 on `main`; D2 (envelope, safety numbers) fixed; D3 (encrypted database) in its fix round |
| E, WebRTC and calls | 6 | 0 | E1 reviewed, held for E2's first build; E2 building on Linux CI; E3 (call rules) in review |
| F, push | 4 | 2 | F1 and F2 on `main`; F3 (registration) in review |
| G, CI | 4 | 1 | G1 on `main`; G2 (CI jobs) in progress |
| I, integration and iPhone↔Android test | 2 | 0 | waits for all lanes |

32-bit (`armeabi-v7a`) support is on `main`.

ETA: about 3 days of agent work.

## Admin dashboard ([plan](docs/superpowers/plans/2026-10-28-admin-dashboard.md))

The dashboard is static files embedded in the server binary and served only on the admin port. It has three parts:

- **Overview:** invites, accounts and devices, reports, read-only server settings, and the audit log.
- **Plan:** X1 (setup) is in progress. After it, three lanes run in parallel: login and audit, data API, and pages and guide.
- **ETA:** about 1 day.

## Main CI

The Apple apps workflow on `main` stays red until the Swift 6.2 fix on `fix/swift62-ci` lands; its full Mac run is in progress.

## Waiting on the owner

1. **Approve `androidx.biometric`** for fingerprint unlock on Android. It already comes in through the approved Credential Manager library.
2. **The final app ID.** It is set in `app-id.properties` on Android, and the iPhone/Mac file is coming. It can't change after the first store release. The placeholder is `org.example.app`.
3. **Android items still open:**
   - whether the Rust MLS state files are encrypted on Android
   - a `quiet` flag for receipts, edits and deletes, to save Google push quota
   - a build without Firebase, for F-Droid
   - the CPU threshold for hiding enhanced noise suppression, after measuring on the OnePlus 5
   - the Play data safety answers and the account-deletion web page
   - a self-hosted runner for automated iPhone↔Android call tests
4. **Before App Store submission:**
   - the Mac camera permission
   - Apple's notification-filtering permission
   - how crash reports are sent and kept, marked "TO BE FILLED IN" in the privacy policy
5. **Optional:** override any admin-dashboard default. Emails are masked, report text is hidden, the policy is read-only, the audit log is kept for 365 days, and deleted users aren't emailed.
6. **Set aside until the end of Phase 1:**
   - the owner review of sensitive code
   - the independent security review
   - on-device measurements (L10)
   - the USB-microphone check of the Mac rig
