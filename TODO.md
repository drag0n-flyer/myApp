# Progress

Last updated: 2026-10-28, 15:30 UTC.

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 125 / 128 | 98% |
| Android | 40 / 44 | 91% |
| Admin dashboard | done, with admin accounts and 2FA, on `main` | 100% |

Up to 15 agents work in parallel (on Opus until 2 Nov, when Sonnet's weekly limit resets). CI is the bottleneck: about 5 Mac jobs at once, and GitHub throttles status checks when many agents watch runs. Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

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

Admin dashboard: `http://localhost:9044/admin/`. `.env` also sets `APP_ADMIN_HOSTS=192.168.0.174` and `APP_ADMIN_ALLOW_HTTP=true`, so `http://192.168.0.174:9044/admin/` works on the LAN (sign-in crosses the LAN unencrypted; owner's choice). First visit: "Create the first admin" (password 12+ characters; username lowercase letters, digits, `.`, `_`, `-`), or `bin/appserver admin user add <name>`. If the browser still shows an old token field, reload with Ctrl+Shift+R.

## iPhone/Mac Phase 1, by plan

| Plan | Done | % | Now | ETA |
|---|---|---|---|---|
| [Go server](docs/superpowers/plans/2026-10-27-go-server.md) | 27 / 27 | 100% | on `main`, plus Android devices, FCM/UnifiedPush, `.env` loading, the Makefile and the admin dashboard | done |
| [App UI on sample data](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md) | 16 / 16 | 100% | on `main` | done; Mac UI test confirmed on the next green run |
| [Client networking](docs/superpowers/plans/2026-10-27-client-networking.md) | 14 / 14 | 100% | on `main`, with end-to-end tests against the real Go server in CI | done |
| [MLS client core](docs/superpowers/plans/2026-10-27-mls-client-core.md) | 7 / 7 | 100% | on `main`; the real-iPhone checklist for the notification extension is yours (see "Waiting on the owner") | done |
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 6 / 6 | 100% | Release `webrtc-m156.1` published; the package is pinned to it and all Apple CI jobs, including the real call-media tests, are green. Open, minor: add WebRTC's PATENTS file to the generated license notice and fix the `&quot;` escaping (in `package.sh`); the bitrate test skips the 10 kbps target check when the runner reports no target | done |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 16 / 16 | 100% | I1 merged. With the real WebRTC build the encoder holds 10 kbps (about 19 kbps on the wire, inside GOAL.md's 17–21); the send-bitrate test now checks the audio payload, the encoder target and the wire total | done |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 16 / 16 | 100% | I1 merged: live store, calls tied to the caller's device, CallKit hang-up, sign-out ends calls first, a setup-error screen instead of sample chats; Apple CI green | done |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 13 / 14 | 93% | only Task 13 left, deferred by the owner | done for now |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 10 / 12 | 83% | L10a merged: a weekly Mac job runs 7 test calls between two Mac app processes under simulated networks (labelled "CI, simulated"); its first run is the real test of the network shaping. Four rulings for you to confirm in the decision log. L10 deferred (release gate); L11 next | about 0.5 day |

Extra work beyond the plans, in progress (each is reviewed before it merges; ETAs are agent time):

| Work | Branch | Now | ETA |
|---|---|---|---|
| Android review follow-ups (server binding, invite clearing, 1:1 call signals) | `android/followups` | merged | done |
| Port: a lost reply to a new chat's first commit no longer orphans it; one bad invite no longer stalls the inbox | `fix/ios-port-inbox` | merged | done |
| Port: calls check the answering device; ring (50 s) and setup (30 s) deadlines | `fix/ios-port-calls` | merged | done |
| Port: own devices kept in line with the account; old control messages expire after 24 h | `feat/ios-port-reconcile` | merged | done |
| Android: the same own-device wiring, with the same protection | `android/reconcile-wiring` | merged (only a rejected session wipes a phone; switched on by Android I1) | done |
| Port: sign-up sends the display name, email-code sign-up, read-receipts switch | `feat/ios-port-signup` | merged | done |
| Port: linking skips an unreadable or too-large contact list; link-code check compares the port | `feat/ios-port-linking` | merged | done |
| Small follow-ups: a pending link save stays tied to the server that issued it (switching server is refused meanwhile); after a claimed link only the session save is retried; read-receipt outbox tests | `fix/ios-followups` | merged (CI green, all 8 jobs). Open, low: capture the server at the start of the link wait, so a "Change" tap at the instant the claim succeeds can't file the session under the new server | done |
| Android E6: Telecom calls and LiveCallEngine; sign-out and wipe end any call first | `android/e6` | merged (CI green on re-runs). Open, low: close the media in the 3 s hang-up timeout branch too (do it in E5); flaky `concurrentOpensAfterDeviceSwitchLeaveOneSession` (from I1, capture the failing assertion); `VoiceRecorderInstrumentedTest` timing window too tight on the API 36 emulator | done |
| **Security fix:** iPhone safety numbers were built from placeholder keys while the screen showed them; switch to the real MLS signature keys | `fix/ios-real-member-keys` | written, CI green; review asked that an updated install never shows or lets you verify the old number; fixing | 1–2 h |
| **Security follow-up (found in review, already on main):** if any member's name in a chat can't be read, the iPhone silently stops reporting device and key changes for that whole chat (`mlsffi/src/engine.rs:394-399`, `InboxProcessor.swift:377`) | not started | queued after the fix above | 2–3 h |
| Port: a 401 wipes only when the server's own error and a second check (`GET /v1/me`) both confirm it; 4003 always wipes; local data records its device, and a sign-out with no recorded device wipes | `fix/ios-401-confirm` | merged (security review approved, CI green). Open, minor: the notification extension doesn't check who owns the data before opening it | done |
| Android I2: Android and iPhone peers talk through one server (texts, photos, calls' signalling, linking, safety numbers); first Mac run green (18 steps passed, 1 honest skip: the iPhone app does not import the link snapshot yet, plan 7). Device checklist for you in `docs/release/android-device-checklist.md` | `android/i2` | merged | done |
| App ID in one file (`apps/apple/AppID.xcconfig`) and a stronger photo-privacy test | `chore/appid-xcconfig` | merged (CI green). Open, low: `check-app-id.sh` also scans git-ignored build output, so it fails on a local checkout that has built the MLS Kotlin bindings; CI is clean | done |

Android has the same inbox fixes (merged): a crafted invite can't stall the inbox, a new chat is abandoned only when really gone, and a failure after a chat starts never creates a second one.

## Main CI

Green: Apple apps (every job, after the Swift 6.2 / Xcode 26 fix), MLS client core, Android, security. Follow-ups from its review:
- the photo test is being strengthened (see the iPhone/Mac list above)
- the Mac UI tests run without the app's sandbox, app-group and keychain permissions (a test build can't launch with them unsigned); a signed check of those is needed before release
- CallKit calls now report from CallKit's own queue; check calls once on a real iPhone

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI | 9 | 9 | done |
| B, networking and accounts | 7 | 7 | done, with B7's review follow-ups merged (the http/https choice is saved with a session, a link code is claimed only on the server that issued it, changing server clears the invite code) |
| C, MLS bindings | 4 | 4 | done |
| D, storage and messaging | 7 | 7 | done. D7 took M7's `decryptApplication` from the iPhone side; checking `call.status` for late ring pushes moved to E6/I1. A ring is accepted only from the 1:1 chat with its sender (merged) |
| E, WebRTC and calls | 6 (+E5b) | 3 | E3, E4, E5 part 1 and E6 (Android phone-call integration: Telecom, LiveCallEngine, sign-out ends calls) on `main`. E2 (WebRTC build): all three ABIs build and the app now installs on the emulator; the smoke test found the generated JNI classes missing from the jar, fix on CI (run 40691878653). Then E1, E5 part 2 (real media session, loopback and bitrate tests), E5b | E2 1–2 h, lane 1.5–2 days |
| F, push | 4 | 4 | done |
| G, CI | 4 | 4 | done; G4: release bundle, license notices, Play documents. A `v*` tag release fails until the SQLCipher, WebRTC and Opus notice texts are filled in |
| I, integration and iPhone↔Android test | 2 | 1 | I1 merged: the app runs on the live server (accounts, messaging, push, device cleanup; crash-safe wipe). Calls switch on with E6. I2 (iPhone↔Android test) waits for calls |

ETA: about 1.5 days of agent work.

## Admin dashboard ([plan](docs/superpowers/plans/2026-10-28-admin-dashboard.md))

On `main`: overview, invites, accounts and devices, reports, read-only settings, audit log, served only on the admin port.

Added 2026-10-29 (owner decisions), reviewed twice, on `main`:
- **Username and password only.** Token sign-in is removed.
- **First visit:** "Create the first admin" whenever no admin exists, nothing else required.
- **Admins page:** add, disable, enable, reset password, remove; the last working admin can't be removed.
- **Optional two-step sign-in (TOTP)** per admin: scan a QR code, confirm with a code. Lost phone: `bin/appserver admin user reset-2fa <name>` (no recovery codes).
- **Plain HTTP on the LAN** for hosts in `APP_ADMIN_HOSTS` when `APP_ADMIN_ALLOW_HTTP=true` (default off; logs a warning).
- **CLI recovery:** `bin/appserver admin user list|add|reset-password|reset-2fa|disable|enable`; passwords are typed without echo.

Follow-ups from the last review are merged too (one session per pending login, escaped QR label, turning on two-step sign-in asks for the password).

## Waiting on the owner

1. **The final app ID.** Android reads it from `app-id.properties`, the iPhone/Mac from `apps/apple/AppID.xcconfig`. It can't change after the first store release. The placeholder is `org.example.app`.
2. **A domain and HTTPS for testing on phones.** The server speaks plain HTTP; the apps, passkeys and the admin dashboard from other machines need HTTPS with a real certificate. A domain pointed at this machine plus Caddy (in the Docker setup) is enough.
3. **SMTP details in `.env`** (`APP_SMTP_URL`, `APP_MAIL_FROM`), so sign-up codes are sent.
4. **Admin port on the LAN (your choice, noted):** `.env` has `APP_ADMIN_LISTEN=0.0.0.0:9044` and plain HTTP allowed. Create your admin now (restart the server first): until the first admin exists, anyone on the network who opens the page can create it. Then turn on two-step sign-in.
5. **Run the device checklist on a real iPhone** once M6's fix lands (`apps/apple/Prototypes/NSEPrototype/DEVICE-CHECKLIST.md`): set your Apple team, install, send a test push. GitHub's simulators can't run the notification extension.
6. **Protocol rulings made for you, to confirm:**
   - the first commit of a new chat may be resent once if its reply was lost (only at epoch 0, when the creator is the only member); written into `docs/protocol/mls-profile.md`, marked "owner to confirm"
   - call glare: when both people call at once, a caller whose own offer is 5 s old gives way (carried over from the iPhone)
   - a stranger's call, or a call that ends before it is answered, shows a quiet "Missed call" notification on Android
7. **Later, cross-platform:** the server should recognise a resent commit by its SHA-256, so a chat whose commit request was lost doesn't stay stuck for membership changes (messages still flow).
8. **Android items still open:**
   - whether the Rust MLS state files are encrypted on Android
   - a `quiet` flag for receipts, edits and deletes, to save Google push quota
   - Android 10 on some phones accepts a weak face unlock as the app-unlock fallback (accepted ruling; revisit if the gate is ever tied to a key)
   - a build without Firebase, for F-Droid
   - the CPU threshold for hiding enhanced noise suppression, after measuring on the OnePlus 5
   - the Play data safety answers and the account-deletion web page
   - confirm the 14 Google libraries in the license allowlist (pulled in by Firebase messaging and Google sign-in; one, `play-services-identity-credentials`, is an alpha release)
   - the SQLCipher license text (not in the downloaded files)
   - a self-hosted runner for automated iPhone↔Android call tests
9. **Before App Store submission:**
   - the Mac camera permission
   - Apple's notification-filtering permission
   - how crash reports are sent and kept, marked "TO BE FILLED IN" in the privacy policy
