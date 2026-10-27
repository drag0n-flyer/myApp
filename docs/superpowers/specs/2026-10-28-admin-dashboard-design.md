# Admin Dashboard Design

**Status:** draft for owner review. **Owner request (2026-10-28):** "build one admin dashboard, in server binary it run, no fullstack, only static files". **Builds on:** [server design](2026-10-27-server-design.md) §3–§9, [GOAL.md](../../../GOAL.md) §6–7 and §16. **Plan:** [2026-10-28-admin-dashboard.md](../plans/2026-10-28-admin-dashboard.md).

This request moves "an admin web UI" out of the server design's §1 "Out" list. The admin CLI stays, and the dashboard adds no ability that the CLI or the database does not already give the operator, with one exception: device revocation and account deletion by the operator (§6.3).

Choices this document makes without an owner instruction are marked "(planner's choice)". Questions only the owner can answer are in §11.

## 1. Shape

- **One package, `server/internal/admin`.** It holds the JSON admin API, the login, the audit log and the static files. The static files live in `server/internal/admin/web/` and are embedded with `//go:embed web`. Nothing is read from disk at run time.
- **Plain HTML, CSS and vanilla JavaScript.** No framework, no npm or node, no build step, no CDN, no web fonts, no new Go dependency. The JavaScript is plain ES2020 that the browser runs as written: one file per page plus one shared file.
- **Admin listener only.** The admin package never registers anything on the public `httpx.Router`. `App.AdminHandler()` mounts it next to `/healthz`, `/readyz` and `/debug/vars`, which stay as they are, with no login, for uptime checks.
- **Read through SQL, write through the owning module.** The admin package reads accounts, devices, invites, reports and counts with its own read-only SQL. Every change goes through a function the owning module exports, so side effects (closing a socket, telling the other devices) stay in one place:

  | Change | Owner | Exported function |
  |---|---|---|
  | Create an invite | `identity` | `CreateInvite` (exists) |
  | Revoke an invite | `identity` | `RevokeInvite(ctx, id string) error` |
  | Revoke a device | `identity` | `AdminRevokeDevice(ctx, deviceID string) error` |
  | Delete an account | `identity` | `AdminDeleteAccount(ctx, accountID string) error` |
  | Mark a report handled | `social` | `MarkReportHandled(ctx, id, tokenID string) error` |

- **Live numbers through small read-only methods** that `app` wires in: `realtime.(*Module).ConnectionCount() int`, `push.(*Module).Stats() push.Stats` (sent and failed since start), and `turn.(*Module).Relays() []turn.RelayStatus` (host, region, healthy, last round-trip time).

## 2. Reaching the dashboard

- `APP_ADMIN_LISTEN` keeps its default `127.0.0.1:9090`. Under compose the container listens on `0.0.0.0:9090` and the host publishes it on `127.0.0.1:9090` only (as today).
- The operator opens an SSH tunnel and browses to `http://localhost:9090/admin/`:

  ```
  ssh -N -L 9090:127.0.0.1:9090 operator@chat.example.org
  ```

- **Host check (DNS rebinding guard).** Every request on the admin listener under `/admin` must carry a `Host` of `localhost`, `127.0.0.1` or `[::1]` (any port), or one listed in the new optional variable `APP_ADMIN_HOSTS` (comma list, for an operator who puts their own TLS proxy in front). Anything else gets `421 Misdirected Request` with no body. (planner's choice)
- A host from `APP_ADMIN_HOSTS` is treated as remote: the cookie gets `Secure`, so the login works only over HTTPS. (planner's choice)

## 3. Login

1. On the server: `appserver admin token create [--label TEXT] [--minutes N]`. It prints one token, once, and stores only its SHA-256.
2. In the browser the operator pastes it into the login page. `POST /admin/api/login` exchanges it for a session cookie. The token is used up by that exchange, even if the browser never stores the cookie.
3. A new login needs a new token. The operator is already on the host over SSH, so this costs one command.

Values (all planner's choice):

| Item | Value |
|---|---|
| Token format | `adm_` + 32 random bytes, base64url without padding |
| Token lifetime if unused | `--minutes`, default 15, at most 60 |
| `--label` | at most 64 characters, no control characters; shown in the audit log |
| Session ID | 32 random bytes, base64url; the database stores only its SHA-256 |
| Idle timeout | 15 minutes without a request |
| Absolute lifetime | 8 hours from login |
| Cookie | `admin_session`, `Path=/admin`, `HttpOnly`, `SameSite=Strict`, no `Max-Age` (ends with the browser), `Secure` unless the request's `Host` is a loopback name (§2) |
| Login rate limit | 5 per minute per client address, and 30 per hour for the whole server (`ratelimit.Limiter`, keyed by `httpx.ClientIP`; the address is never stored or logged) |
| Wrong, used, expired or revoked token | the same `401 unauthorized` body for each, compared by hash lookup (a constant-time compare of the hash is not needed: the lookup key is a hash of 32 random bytes) |

`appserver admin sessions end` ends every dashboard session at once (for a lost laptop). (planner's choice)

Expired tokens and sessions are deleted by the admin module's hourly `Run`.

## 4. Browser protections

- **CSRF.** Each session has its own CSRF token (32 random bytes, stored as SHA-256). `GET /admin/api/session` returns it; the JavaScript keeps it in memory and sends it as `X-CSRF-Token` on every request whose method is not `GET` or `HEAD`. Such requests also need:
  - `Content-Type: application/json` (a cross-site form cannot send it without a preflight, which the server never answers);
  - `Sec-Fetch-Site`, when present, equal to `same-origin`;
  - `Origin`, when present, equal to `http(s)://<Host>`.
  The login request has no session yet, so it skips the token but keeps the other three checks. (planner's choice: synchronizer token plus header checks)
- **Response headers** on every `/admin` response:

  ```
  Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  Referrer-Policy: no-referrer
  Cross-Origin-Opener-Policy: same-origin
  Cross-Origin-Resource-Policy: same-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  Cache-Control: no-store
  ```

  Static files get `Cache-Control: no-cache` instead, so a new binary's files load at once.
- **No inline code.** No inline `<script>`, no `on…=` attributes, no `style=` attributes, no `javascript:` URLs. The JavaScript writes text with `textContent` only, never `innerHTML`, so a username or device name cannot become markup.
- **Without JavaScript** the pages show a short `<noscript>` note. Read-only pages without JavaScript would need server-rendered templates for every page, which is not cheap, so they are not built. (planner's choice)

## 5. Data rules

The dashboard never shows:
- message content, attachment content, or escrow plaintext (escrow export stays CLI-only: it needs a stated reason and is logged in `escrow_access_log`);
- session tokens, admin tokens, push handles, link secrets, email codes, or IP addresses;
- secrets from the configuration: `APP_SMTP_URL`, `APP_DATABASE_URL`, `APP_TURN_SECRET`, or the contents or paths of key files (the page shows only "configured" or "not configured").

Other values:
- **Email addresses** are masked as the first character of the local part, `•••`, `@` and the full domain: `ana@example.org` becomes `a•••@example.org`. The full address is never sent to the browser. (owner decision O-1)
- **Invite codes** are shown in full only once, in the answer to "create". Lists show the first group (`ABCDE-…`) and an internal ID. (planner's choice: an invite code works like a password for sign-up)
- **Report text** (the shared messages and the note) is not shown by default. The reports module gives it to the operator only by email to `abuseContact`; `reports list` shows only counts. Showing it in the dashboard is owner decision O-2.
- **Usernames, display names, device names, platforms and times** are shown. Server design §9 lists them as operator-visible.

Lists are paged with a keyset cursor, 50 rows per page. (planner's choice)

## 6. Features

### 6.1 Overview

Health (database ping), version (`vcs.revision` and `vcs.time` from `debug.ReadBuildInfo`, else `unknown`), Go version, start time and uptime, and these counts:

| Number | Source |
|---|---|
| Accounts, devices, conversations | `select count(*)` on `accounts`, `devices`, `conversations` |
| Messages today (UTC day) | `client_messages` rows with `created_at` since UTC midnight (they are kept 7 days) |
| Live connections | `realtime.ConnectionCount()` |
| Pushes sent and failed since start | `push.Stats()`; "off" when no gateway is set |
| TURN relays | `turn.Relays()`: host, region, healthy, last round-trip time; "calls off" when `APP_TURN_SECRET` is unset |

The page reloads its numbers every 30 seconds while visible. (planner's choice)

### 6.2 Invites

List (state `active`, `used up`, `expired` or `revoked`; uses left; created; expires), create (uses 1–100, days 1–90; defaults 1 and 14, as the CLI), and revoke. Revoking sets `uses_left = 0` and `revoked_at`. The answer to create shows the full link `https://<APP_HOST>/invite/<code>` once, with a copy button.

### 6.3 Accounts

- Search by username prefix (lowercase, at least 1 character, same character set as usernames). Rows: username, display name, masked email, created, number of devices.
- Account page: the same fields and its devices (name, platform, created, last seen).
- **Revoke a device:** a confirm dialog naming the device. It does what `DELETE /v1/devices/{id}` does: delete the row, close the socket with 4003, and send `device.revoked` to the account's other devices.
- **Delete an account:** the operator types the username to confirm, and picks a reason: `user_request`, `abuse` or `other` (stored in the audit entry; no free text, so no personal data lands in the log; planner's choice). It does what `DELETE /v1/me` does.

### 6.4 Reports

List: created, reason, reported account (username, or "deleted account"), reporter (username or "deleted"), conversation ID if given, number of shared messages, whether a note exists, and handled (time and the admin token ID). Filter `open` (default), `handled`, `all`. Detail page: the same, plus (only if O-2 is approved) the shared messages and the note. "Mark handled" sets `handled_at` and `handled_by`.

### 6.5 Server

Read-only. The loaded policy (the `config.Policy` JSON), the server info document (`GET /v1/server`, same bytes), and non-secret settings: host, public and admin listen addresses, relays, push gateway URL, whether escrow, SMTP, docs and lab mode are on, trust-proxy. If lab mode is on, the page shows the warning from the admin guide in a banner.

Editing is not built. `core.Deps.Policy` is copied by value into every module at start, so a live change would reach some modules and not others, and the compose file mounts the policy read-only. The operator edits the file and restarts. (owner decision O-3)

### 6.6 Audit log

Newest first: time, actor (`dashboard` with the token's ID and label, or `cli`), action, target. Filter by action.

## 7. Admin API

All paths are under `/admin/api`. JSON in and out, with the server's error envelope (server design §7) and its codes, plus HTTP 421 for a wrong `Host` (no body). Every endpoint except `login` needs the session cookie: no cookie, or an expired one, is `401 unauthorized`; a missing or wrong CSRF token is `403 forbidden`.

| Method and path | Request → response |
|---|---|
| `POST /login` | `{token}` → `204` and `Set-Cookie`; `unauthorized`, `rate_limited` |
| `POST /logout` | → `204`, clears the cookie |
| `GET /session` | → `{tokenID, label, csrf, idleTimeoutSeconds:900, expiresAt}` |
| `GET /overview` | → `{version, goVersion, startedAt, uptimeSeconds, database:"ok"\|"down", counts:{accounts, devices, conversations, messagesToday}, connections, push:{enabled, sent, failed}, calls:{enabled, relays:[{host, region, healthy, rttMs}]}}` |
| `GET /invites?state=&after=` | → `{invites:[{id, codePrefix, usesLeft, createdAt, expiresAt, revokedAt, state}], next}` |
| `POST /invites` | `{uses, days}` → `{id, code, link, expiresAt}`; `bad_request` |
| `POST /invites/{id}/revoke` | → `204`; `not_found` |
| `GET /accounts?q=&after=` | → `{accounts:[{id, username, displayName, email, createdAt, devices}], next}` (`email` masked) |
| `GET /accounts/{id}` | → `{id, username, displayName, email, createdAt, devices:[{id, name, platform, createdAt, lastSeenAt}]}` |
| `POST /accounts/{id}/devices/{deviceID}/revoke` | → `204`; `not_found` (also when the device is not this account's) |
| `POST /accounts/{id}/delete` | `{confirmUsername, reason}` → `204`; `bad_request` if the name does not match exactly or the reason is unknown |
| `GET /reports?state=&after=` | → `{reports:[Report], next}` |
| `GET /reports/{id}` | → `Report` (plus `{messages:[{sentAt, text}], note}` only if O-2 is approved) |
| `POST /reports/{id}/handle` | → `204`; marking a handled report again is `204` and changes nothing |
| `GET /server` | → `{policy, info, settings}` |
| `GET /audit?action=&after=` | → `{entries:[{id, at, actor, tokenID, tokenLabel, action, target, detail}], next}` |

`Report = {id, createdAt, reason, reportedAccountID, reportedUsername, reporterAccountID, reporterUsername, conversationID, messageCount, hasNote, handledAt, handledBy}`; usernames are `null` for a deleted account. `next` is an opaque cursor, or `null` on the last page.

Static routes: `GET /admin/` (`index.html`, the overview), `GET /admin/{page}.html` for `login`, `invites`, `accounts`, `account`, `reports`, `report`, `server`, `audit`, and `GET /admin/static/{file}` for `admin.css`, `common.js` and one script per page. `GET /` on the admin listener redirects to `/admin/`. A page whose session check fails sends the browser to `login.html`.

## 8. Audit log

There is no general audit table today (`escrow_access_log` covers escrow export only, and stays as it is). Migration `6001_admin.sql` adds one; the `6xxx` prefix is new and reserved for `admin` (planner's choice, following server design §6).

```sql
create table admin_tokens (
    id         uuid primary key,
    token_hash bytea not null unique,
    label      text not null default '',
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz
);
create table admin_sessions (
    id_hash      bytea primary key,
    token_id     uuid not null references admin_tokens (id) on delete cascade,
    csrf_hash    bytea not null,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null,
    expires_at   timestamptz not null
);
create table admin_audit (
    id       bigserial primary key,
    at       timestamptz not null,
    actor    text not null check (actor in ('dashboard', 'cli')),
    token_id uuid,
    action   text not null,
    target   text not null default '',
    detail   jsonb not null default '{}'
);
create index admin_audit_at_idx on admin_audit (at desc, id desc);
```

`admin_audit.token_id` has no foreign key, so an entry outlives its token. Used tokens are kept as long as the audit entries that name them; only unused, expired tokens are deleted.

Actions and targets:

| Action | Actor | Target | Detail |
|---|---|---|---|
| `token.create` | cli | token ID | `{label}` |
| `sessions.end` | cli | — | `{count}` |
| `login` | dashboard | token ID | — |
| `login.failed` | dashboard | — | — (the presented token is never stored) |
| `logout` | dashboard | token ID | — |
| `invite.create` | dashboard | invite ID | `{uses, days}` |
| `invite.revoke` | dashboard | invite ID | — |
| `account.view` | dashboard | account ID | — |
| `device.revoke` | dashboard | device ID | `{accountID}` |
| `account.delete` | dashboard | account ID | `{reason}` |
| `report.view` | dashboard | report ID | — |
| `report.handle` | dashboard | report ID | — |

Targets are IDs only: no usernames, emails or search text, so the log keeps no personal data after an account is deleted. (planner's choice) The audit row is written in the same transaction as the change when the change is SQL in the admin package, and right after the owning module's function returns otherwise; if that second write fails, the server logs `admin audit write failed` with the action and target ID and answers `500`, so the operator sees that something went wrong. Entries are kept 365 days, then deleted by the hourly `Run` (owner decision O-4).

Logs follow server design §8: the admin package logs action names and IDs at debug level only, and never tokens, cookies, CSRF tokens, emails or addresses.

## 9. Other changes

- **Migrations:** `1004_invite_admin.sql` (`invites` gains `id uuid not null default gen_random_uuid() unique`, `created_at timestamptz not null default now()`, `revoked_at timestamptz`); `5002_report_handled.sql` (`reports` gains `handled_at timestamptz`, `handled_by uuid`); `6001_admin.sql` (§8).
- **`app`:** the CLI dispatcher also matches three-word commands (`admin token create`), longest first; `AdminHandler` mounts the admin package; `app.New` records the start time.
- **`config`:** `APP_ADMIN_HOSTS` (comma list, default empty).
- **`httpx`:** exports `WriteError(w http.ResponseWriter, err error)` so the admin package answers with the same envelope.
- **Docs:** the admin guide gains "Admin dashboard" (tunnel, token, what it shows and hides, `APP_ADMIN_HOSTS`); server design §1 and §5 point here.

## 10. Accessibility and look

The app UI rules do not apply. Still:
- semantic HTML: `<header>`, `<nav>`, `<main>`, real `<table>` with `<th scope>`, `<label>` on every input, `<button>` for actions, `<dialog>` for confirms;
- a visible focus ring, a "Skip to content" link, and status messages in an `aria-live="polite"` region;
- system fonts (`system-ui`), colors as CSS custom properties with a `prefers-color-scheme: dark` set, contrast at least 4.5:1;
- plain words: "Live connections", "Pushes sent", "Call relays", "Mark handled". "Call relays" is the admin guide's own term for TURN hosts; otherwise no protocol jargon where a plain word works.

## 11. Owner to decide

| # | Question | Recommendation |
|---|---|---|
| O-1 | Email addresses: masked, or in full? | Masked (`a•••@example.org`) everywhere. The operator who needs the full address has the database. |
| O-2 | Show report text (shared messages and note) on the report page? | Yes, on the detail page only, with a `report.view` audit entry. The reporter shared it for the operator, the privacy policy §2 already says the operator can read it, and acting on a report without it is hard. Until approved it is not built. |
| O-3 | Policy editing in the dashboard? | No. Keep it read-only; editing needs a live policy reload across modules, which is a separate design. |
| O-4 | Audit log retention, and a line in the privacy policy? | 365 days. Ask the lawyer to add the audit log (operator actions and the IDs they touched) to privacy policy §6. |
| O-5 | Tell a user by email when the operator deletes their account? | Not in Phase 1. The operator contacts them if needed. |
