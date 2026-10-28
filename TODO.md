# Progress

Last updated: 2026-10-29 (morning).

| Track | Done | % |
|---|---|---|
| iPhone/Mac Phase 1 | 120 / 128 | 94% |
| Android | 37 / 44 | 84% |
| Admin dashboard | done, with admin accounts and 2FA, on `main` | 100% |

Up to 15 agents work in parallel (on Opus until 2 Nov, when Sonnet's weekly limit resets). Mac CI runs only the jobs a change needs. Android CI uses Linux runners only.

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
| [WebRTC + Opus build](docs/superpowers/plans/2026-10-27-webrtc-opus-build.md) | 4 / 6 | 67% | Task 5: Mac CI build; then Task 6, the first release | 1 day |
| [Client calling](docs/superpowers/plans/2026-10-27-client-calling.md) | 15 / 16 | 94% | I1 left | 1 day after WebRTC |
| [Client messaging](docs/superpowers/plans/2026-10-27-client-messaging.md) | 15 / 16 | 94% | I1 (integration with calling) written, Linux tests pass; getting the iPhone and Mac app builds green on CI | 0.5 day |
| [Security docs and release](docs/superpowers/plans/2026-10-27-security-docs-release.md) | 13 / 14 | 93% | only Task 13 left, deferred by the owner | done for now |
| [Call lab](docs/superpowers/plans/2026-10-27-call-lab.md) | 9 / 12 | 75% | L10a waits for WebRTC; L10 deferred; L11 last | 1 day after WebRTC |

In progress for the iPhone/Mac (one agent each, started 2026-10-28):
- the app ID moves into one file, `apps/apple/AppID.xcconfig`
- bugs the Android port found that the iPhone shares: a chat can be orphaned if the reply to its first commit is lost; one bad join can stop the whole inbox
- the call machine should check the answering device against its encryption identity, and give up on calls that never connect (ring and setup deadlines)
- sign-up sends the display name (`PATCH /v1/me/settings` after sign-up) and has an email-code path that never registers a passkey, matching Android
- the read-receipts switch works (off stops sending read receipts and hides others' read ticks; not retroactive), matching Android
- linking: a contact list that can't be opened is skipped (sign in, tell the user) instead of failing the link; a too-large one is skipped with a note to the approver; the link-code check must compare the port too
- own-device reconcile (protocol requirement) with Android's safety checks; control messages in the outbox expire after 24 h

## Main CI

The Apple apps workflow fix (Swift 6.2 / Xcode 26) is merged; every Apple job was green on its branch. Follow-ups from its review:
- the photo test checks only three metadata keys and starts from a picture with no metadata; it should start from a photo carrying GPS, camera and date data and allow only size and colour keys
- the Mac UI tests run without the app's sandbox, app-group and keychain permissions (a test build can't launch with them unsigned); a signed check of those is needed before release
- CallKit calls now report from CallKit's own queue; check calls once on a real iPhone

## Android, by lane ([plan](docs/superpowers/plans/2026-10-28-android-client.md))

| Lane | Tasks | Done | Now |
|---|---|---|---|
| A, scaffold and UI | 9 | 9 | done |
| B, networking and accounts | 7 | 7 | done. Small follow-ups from B7's last review: save the http/https choice with a local dev session, check the linking server before claiming, clear the invite code when the server is changed during sign-up |
| C, MLS bindings | 4 | 4 | done |
| D, storage and messaging | 7 | 7 | done. D7 took M7's `decryptApplication` from the iPhone side; checking `call.status` for late ring pushes moved to E6/I1 |
| E, WebRTC and calls | 6 (+E5b) | 2 | E3, E4 on `main`; E2 (WebRTC build) on CI, last 32-bit link fix; E1 after E2; then E5, E5b (bundled Opus for phones without an encoder), E6 |
| F, push | 4 | 4 | done |
| G, CI | 4 | 4 | done; G4: release bundle, license notices, Play documents. A `v*` tag release fails until the SQLCipher, WebRTC and Opus notice texts are filled in |
| I, integration and iPhone↔Android test | 2 | 0 | waits for all lanes |

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

1. **The final app ID.** Android reads it from `app-id.properties`; the iPhone/Mac file is coming. It can't change after the first store release. The placeholder is `org.example.app`.
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
