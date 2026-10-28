# Progress

Last updated: 2026-10-29, 03:20 IST (21:50 UTC Oct 28).

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 127 / 128 | 99% |
| Android | 44 / 44 | 100% |
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
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 14 / 14 | 100% | Task 13 merged: code owners, `scripts/setup-branch-protection.sh` (dry-run tested; NOT applied, because it would block the agents' direct merges) and `docs/ops/repo-setup.md`. Yours: before applying it, make the server, apple and docs workflows report on every pull request (they run only when their files change), then run the script | done |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 11 / 12 | 92% | L10a and L11 done: the weekly Mac job runs 7 test calls; an `estimates` run measured all 4 modes (3 calls each, "CI, simulated") and both apps now show 2/3/5/6 MB per 10 min (High was 8). Yours: approve or reject the proposed GOAL.md §4 table change (measured 11.9/18.3/27.3/33.6 kbps per direction), and the placeholder line in the UI design spec (line 99). L10 (real phones) deferred; it is the release gate | waiting on you |

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
| Android E6: Telecom calls and LiveCallEngine; sign-out and wipe end any call first | `android/e6` | merged (CI green on re-runs). Open, low: `VoiceRecorderInstrumentedTest` timing window too tight on the API 36 emulator | done |
| **Security fix:** iPhone safety numbers now cover the real signature keys; a number is shown or can be verified only if it matches the number rebuilt from the stored members (closes an oversized-identity route and a stale number after the main conversation switches) | `fix/ios-real-member-keys` | merged (three security review rounds, CI green). Ruling to confirm: one-time silent reset of stored placeholder keys on upgraded installs | done |
| **Security fix:** a member whose name isn't readable text could join a chat and silently stop all device and key-change reporting. The shared encryption core now rejects such members on every way in (iPhone and Android); iPhone shows "Can't check the devices in this chat right now" and hides the safety number instead of failing silently | `fix/mls-unreadable-member` | merged (security review approved). To confirm: the 255-byte name limit (proposed in the protocol doc). Android port merged too (security review approved). Open, minor: two DeviceSync member reads throw without flagging (only groups stored before the fix) | done |
| Port: a 401 wipes only when the server's own error and a second check (`GET /v1/me`) both confirm it; 4003 always wipes; local data records its device, and a sign-out with no recorded device wipes | `fix/ios-401-confirm` | merged (security review approved, CI green). Open, minor: the notification extension doesn't check who owns the data before opening it | done |
| Android I2: Android and iPhone peers talk through one server (texts, photos, calls' signalling, linking, safety numbers); first Mac run green (18 steps passed, 1 honest skip: the iPhone app does not import the link snapshot yet, plan 7). Device checklist for you in `docs/release/android-device-checklist.md` | `android/i2` | merged | done |
| Android: a call ring arriving while the app wiped the old device's data after a device switch was silently dropped (the cause of the "flaky" `concurrentOpensAfterDeviceSwitchLeaveOneSession`); it now waits for the session. Follow-up merged (`fix/android-open-revoke-window`): no session opens across a revocation wipe. Open, very low: if the wipe's local sign-out itself fails in one narrow window, an open could proceed on part-wiped data (re-check `local.pending` after the snapshot, AppGraph.kt:279-281) | `fix/android-flaky-device-switch` | merged (reviewed, CI green) | done |
| **Data use under packet loss (found by the call lab):** on a 5% loss network the lowest data mode used 3–11 MB per 10 min instead of ~2, with the Opus audio unchanged. The extra is WebRTC's bandwidth-estimator padding probes (empty ~76-byte packets) plus extra RTCP. Needs a fix on both platforms (cap or turn off probing and padding in the low-data modes), measured by the call lab | not started | next | ~0.5–1 day |
| Android emulator CI: started up to four emulators at once and the 16 GB runner was shut down; now one at a time | main | fixed | done |
| Android store: foreign keys were off on the database's read connections (writes were always enforced, no bad data possible); now on for every connection, which also fixes the flaky `foreignKeysAreOn` | `fix/android-store-fk` | merged (reviewed, CI green) | done |
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
| E, WebRTC and calls | 6 (+E5b) | 7 | All done on `main`, E5b included: voice notes use the phone's Opus encoder, else the bundled one from WebRTC patch 0008 (release `webrtc-android-m156.2`, +5 KB). Yours: Standard vs Enhanced noise CPU on the OnePlus 5; check that a voice note's first moment isn't cut off on a real phone. Open, low: the bundled encoder aborts the app on out-of-memory instead of reporting it (fix in the next WebRTC rebuild); a phone encoder that fails to start falls back without logging why | done |
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
