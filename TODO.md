# Progress

Last updated: 2026-10-28 (night).

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 118 / 128 | 92% |
| Android | 23 / 43 | 53% |
| Admin dashboard | 15 / 15 written, final review running | 95% |

About 17 agents work in parallel. Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

App code is written on Linux. It counts as done here once it is written, reviewed and merged. It is checked for real only when its CI run is green.

ETAs are agent working time from now.

## Run the server on this machine

```
make env     # once: creates .env from .env.example
make db      # only if you have no PostgreSQL: starts one in Docker
make run     # builds bin/appserver and starts it (log: server/run/appserver.log)
make kill    # stop; make clean removes the binary and log
```

This machine's `.env` uses the local PostgreSQL 18 (user and database `appserver`) and `APP_HOST=192.168.0.174.nip.io`. Passkeys refuse bare IP addresses; nip.io points that name at 192.168.0.174. Phones need HTTPS before they can sign in (see "Waiting on the owner").

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`, plus Android devices, FCM/UnifiedPush, `.env` loading and the Makefile | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main` | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 14 / 14 | 100% | S3 (end-to-end against the real server) green in CI, in review | merge after review |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 5 / 7 | 71% | M6 in progress; M7 in its fix round | 0.5–1 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: Mac CI build; then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | I1 left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1: integration with calling plus CI, in progress | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 13 / 14 | 93% | only Task 13 left, deferred by the owner | done for now |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 9 / 12 | 75% | L10a waits for WebRTC; L10 deferred; L11 last | 1 day after WebRTC |

Queued for the iPhone/Mac after the current work:
- the app ID moves into one file, `apps/apple/AppID.xcconfig`
- two bugs the Android port found that the iPhone shares: a chat can be orphaned if the reply to its first message is lost, and one bad join stops the whole inbox
- the call machine should check the answering device against its encryption identity, and give up on a call that never connects

## Main CI

The Apple apps workflow on `main` is red. The fix on `fix/swift62-ci` makes the Linux packages build with Swift 6.2 and fixes app compile errors from merged lanes; its final Mac run is in progress.

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI | 9 | 5 | A6 (call screens) and A7 (contacts, settings) in review; A8 (onboarding, linking) building; A9 last |
| B, networking and accounts | 7 | 4 | B5 (secure key store) in its fix round; B6, B7 next |
| C, MLS bindings | 4 | 4 | done |
| D, storage and messaging | 7 | 3 | D4 (conversations, inbox) in its fix round; D6 (attachments, voice) in review; D5, D7 next |
| E, WebRTC and calls | 6 | 2 | E3 and E4 on `main`; E2 (WebRTC build) on CI; E1 merges after E2's first green build; E5, E6 after |
| F, push | 4 | 4 | done |
| G, CI | 4 | 1 | G2 (CI jobs) and G3 (emulator tests) in progress; G4 last |
| I, integration and iPhone↔Android test | 2 | 0 | waits for all lanes |

ETA: about 2 days of agent work.

## Admin dashboard ([plan](docs/superpowers/plans/2026-10-28-admin-dashboard.md))

Static pages built into the server binary, served only on the admin port (`127.0.0.1:9090`, reached by `ssh -L 9090:127.0.0.1:9090 your-server`). Overview, invites, accounts and devices, reports, read-only settings, audit log.

All three parts are reviewed and joined, and a smoke run passed. The final review is running; then it merges to `main`.

## Waiting on the owner

1. **The final app ID.** Android reads it from `app-id.properties`; the iPhone/Mac file is coming. It can't change after the first store release. The placeholder is `org.example.app`.
2. **A domain and HTTPS for testing on phones.** The server speaks plain HTTP; the apps and passkeys need HTTPS with a real certificate. A domain pointed at this machine plus Caddy (in the Docker setup) is enough.
3. **SMTP details in `.env`** (`APP_SMTP_URL`, `APP_MAIL_FROM`), so sign-up codes are sent.
4. **Call glare rule:** when both people call each other at once, a caller whose own offer is 5 s old gives way. Carried over from the iPhone and marked "owner to confirm".
5. **Android items still open:**
   - whether the Rust MLS state files are encrypted on Android
   - a `quiet` flag for receipts, edits and deletes, to save Google push quota
   - a build without Firebase, for F-Droid
   - the CPU threshold for hiding enhanced noise suppression, after measuring on the OnePlus 5
   - the Play data safety answers and the account-deletion web page
   - a self-hosted runner for automated iPhone↔Android call tests
6. **Before App Store submission:**
   - the Mac camera permission
   - Apple's notification-filtering permission
   - how crash reports are sent and kept, marked "TO BE FILLED IN" in the privacy policy
