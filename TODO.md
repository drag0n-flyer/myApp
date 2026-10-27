# Progress

Last updated: 2026-10-29 (early morning).

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 119 / 128 | 93% |
| Android | 30 / 43 | 70% |
| Admin dashboard | 15 / 15 on `main`; admin accounts and 2FA being added | 100% + extension |

About 15 agents work in parallel. Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

App code is written on Linux. It counts as done here once it is written, reviewed and merged. It is checked for real only when its CI run is green.

ETAs are agent working time from now.

## Run the server on this machine

```
make env     # once: creates .env from .env.example
make db      # only if you have no PostgreSQL: starts one in Docker
make run     # builds bin/appserver and starts it (log: server/run/appserver.log)
make kill    # stops a server started by make run; make clean removes the binary and log
```

This machine's `.env` uses the local PostgreSQL 18 (user and database `appserver`) and `APP_HOST=192.168.0.174.nip.io`. Passkeys refuse bare IP addresses; nip.io points that name at 192.168.0.174. Phones need HTTPS before they can sign in (see "Waiting on the owner").

Admin dashboard: `http://localhost:9044/admin/`. `.env` also sets `APP_ADMIN_HOSTS=192.168.0.174` and `APP_ADMIN_ALLOW_HTTP=true`, so `http://192.168.0.174:9044/admin/` works on the LAN once admin accounts are merged (sign-in then crosses the LAN unencrypted; owner's choice). Until then the running build still asks for a token from `bin/appserver admin token create --label laptop`.

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`, plus Android devices, FCM/UnifiedPush, `.env` loading, the Makefile and the admin dashboard | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main` | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 14 / 14 | 100% | S3 (end-to-end against the real server) reviewed and fixed, green in CI | merge once main CI is green |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 6 / 7 | 86% | M7 approved and being merged into M6's branch; M6 in progress | 0.5 day |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: Mac CI build; then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | I1 left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1 (integration with calling) written, Linux tests pass; getting the iPhone and Mac app builds green on CI | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 13 / 14 | 93% | only Task 13 left, deferred by the owner | done for now |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 9 / 12 | 75% | L10a waits for WebRTC; L10 deferred; L11 last | 1 day after WebRTC |

Queued for the iPhone/Mac after the current work:
- the app ID moves into one file, `apps/apple/AppID.xcconfig`
- bugs the Android port found that the iPhone shares: a chat can be orphaned if the reply to its first commit is lost; one bad join can stop the whole inbox
- the call machine should check the answering device against its encryption identity, and give up on calls that never connect (ring and setup deadlines)
- sign-up sends the display name (`PATCH /v1/me/settings` after sign-up) and has an email-code path that never registers a passkey, matching Android

## Main CI

The Apple apps workflow on `main` is red. The fix on `fix/swift62-ci` (Linux packages on Swift 6.2, plus app compile errors from merged lanes) is on its last Mac runs. Android CI on `main` is green.

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI | 9 | 8 | A1–A8 on `main`; A9 (accessibility and languages) last |
| B, networking and accounts | 7 | 5 | B1–B5 on `main` (B5: secure key store); B6 (passkeys, fingerprint unlock), B7 (live accounts) next |
| C, MLS bindings | 4 | 4 | done |
| D, storage and messaging | 7 | 5 | D1–D4 and D6 on `main`; D5, D7 next |
| E, WebRTC and calls | 6 | 2 | E3, E4 on `main`; E2 (WebRTC build) on CI; E1 merges after E2's first green build; E5, E6 after |
| F, push | 4 | 4 | done |
| G, CI | 4 | 2 | G1, G2 on `main`; G3 (emulator tests) green on CI, adding the key store and voice recorder device tests; G4 last |
| I, integration and iPhone↔Android test | 2 | 0 | waits for all lanes |

ETA: about 1.5 days of agent work.

## Admin dashboard ([plan](docs/superpowers/plans/2026-10-28-admin-dashboard.md))

On `main`: overview, invites, accounts and devices, reports, read-only settings, audit log, served only on the admin port.

Being added on `feat/admin-users` (owner decisions 2026-10-28):
- **Username and password only.** Token sign-in and `admin token create` are removed.
- **First visit:** "Create the first admin" whenever no admin exists, nothing else required.
- **Admins page:** add, disable, enable, reset password, remove; the last working admin can't be removed.
- **Optional two-step sign-in (TOTP)** per admin: scan a QR code (skip2/go-qrcode, approved), confirm with a code; sign-in then asks for password and code. Lost phone: `bin/appserver admin user reset-2fa <name>` (no recovery codes).
- **Plain HTTP on the LAN** for hosts in `APP_ADMIN_HOSTS` when `APP_ADMIN_ALLOW_HTTP=true` (default off; logs a warning).
- **CLI recovery:** `bin/appserver admin user list|add|reset-password|reset-2fa|disable|enable`; passwords are typed without echo.

Status: accounts reviewed and approved (no serious findings); fix round, plain-HTTP opt-in, token removal and 2FA in progress; then a security review and merge.

## Waiting on the owner

1. **The final app ID.** Android reads it from `app-id.properties`; the iPhone/Mac file is coming. It can't change after the first store release. The placeholder is `org.example.app`.
2. **A domain and HTTPS for testing on phones.** The server speaks plain HTTP; the apps, passkeys and the admin dashboard from other machines need HTTPS with a real certificate. A domain pointed at this machine plus Caddy (in the Docker setup) is enough.
3. **SMTP details in `.env`** (`APP_SMTP_URL`, `APP_MAIL_FROM`), so sign-up codes are sent.
4. **Admin port on the LAN (your choice, noted):** `.env` has `APP_ADMIN_LISTEN=0.0.0.0:9044` and plain HTTP allowed. Create your admin right after the merge: until the first admin exists, anyone on the network who opens the page can create it. Turn on two-step sign-in.
5. **Protocol rulings made for you, to confirm:**
   - the first commit of a new chat may be resent once if its reply was lost (only at epoch 0, when the creator is the only member); written into `docs/protocol/mls-profile.md`, marked "owner to confirm"
   - call glare: when both people call at once, a caller whose own offer is 5 s old gives way (carried over from the iPhone)
   - a stranger's call, or a call that ends before it is answered, shows a quiet "Missed call" notification on Android
6. **Later, cross-platform:** the server should recognise a resent commit by its SHA-256, so a chat whose commit request was lost doesn't stay stuck for membership changes (messages still flow).
7. **Android items still open:**
   - whether the Rust MLS state files are encrypted on Android
   - a `quiet` flag for receipts, edits and deletes, to save Google push quota
   - a build without Firebase, for F-Droid
   - the CPU threshold for hiding enhanced noise suppression, after measuring on the OnePlus 5
   - the Play data safety answers and the account-deletion web page
   - a self-hosted runner for automated iPhone↔Android call tests
8. **Before App Store submission:**
   - the Mac camera permission
   - Apple's notification-filtering permission
   - how crash reports are sent and kept, marked "TO BE FILLED IN" in the privacy policy
