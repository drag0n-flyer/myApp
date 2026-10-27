# Admin Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan runs as one foundation task, then three parallel lanes. Read "Parallel execution" first, then work only the tasks of your lane.**

**Goal:** Add an admin dashboard to `appserver`: static HTML, CSS and JavaScript embedded in the binary, and a small JSON admin API, both served only on the admin listener, behind a one-time-token login, with every admin action in an audit log.

**Architecture:** A new package `server/internal/admin` holds the API, the login, the audit log and the embedded files (`web/`). It reads with its own SQL and changes data only through functions the owning modules export (`identity`, `social`). `app` wires it into `AdminHandler()` and gives it live numbers from `realtime`, `push` and `turn`. A foundation task fixes every file, route and signature with stubs; three lanes then fill disjoint files.

**Tech Stack:** Go 1.26 standard library (`net/http`, `embed`, `crypto/rand`, `crypto/sha256`, `runtime/debug`, `log/slog`), PostgreSQL 18 through the existing `pgx/v5`. HTML, CSS and ES2020 JavaScript with no build step. **No new dependency, Go or otherwise.**

**Spec:** [docs/superpowers/specs/2026-10-28-admin-dashboard-design.md](../specs/2026-10-28-admin-dashboard-design.md), written as D§N. It is the contract for every path, field, value and audit action. The server design is S§N: [2026-10-27-server-design.md](../specs/2026-10-27-server-design.md).

## Global Constraints

- Everything in the server plan's Global Constraints holds: Go 1.26, run commands from `server/`, `TEST_DATABASE_URL` set, `core.Deps.Now` for all time, secrets stored only as SHA-256, never log tokens, cookies, CSRF tokens, emails or addresses (S§8).
- **No new dependency.** No npm, node, bundler, CDN, web font or Go module. `go.mod` must not change.
- The admin package registers **nothing** on the public `httpx.Router`, and has **no** `Routes(*httpx.Router)` method (app discovers modules by method, so such a method would publish it).
- Static files: no inline `<script>`, no `on…=` or `style=` attributes, no `javascript:` URLs, no `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval` or `new Function`. Text goes in with `textContent`.
- The dashboard never receives message content, attachment content, escrow data, report text (until owner decision O-2), tokens, push handles, IP addresses, full emails, or configuration secrets (D§5).
- Migrations: `1004_invite_admin.sql` (lane B), `5002_report_handled.sql` (lane B), `6001_admin.sql` (foundation). No other migration.
- Commits: plain messages, no `Co-Authored-By` or AI trailers.
- Owner review (deferred by the owner's 2026-10-28 decision, but list them in the merge request): `internal/admin/tokens.go`, `auth.go`, `guard.go` (authentication), and the `identity` changes in B2 and B3.

## Review Focus

1. **Nothing admin is reachable on the public port.** Pinned by X1 `TestAdminNotOnPublicHandler` and `TestAdminHasNoPublicRoutes`.
2. **A token works once, is stored only as a hash, and every bad token looks the same.** Pinned by A1 `TestTokenStoredHashed`, A2 `TestTokenWorksOnce` and `TestLoginFailuresLookAlike` (byte-identical bodies).
3. **Sessions end on time.** Idle 15 minutes, absolute 8 hours, and `admin sessions end`. Pinned by A2 `TestSessionIdleTimeout`, `TestSessionAbsoluteLifetime` and A1 `TestSessionsEnd`.
4. **Cross-site requests and rebinding fail.** Pinned by A3 `TestStateChangeNeedsCSRF` (table), `TestHostGuard` and `TestSecurityHeaders`, and C1 `TestStaticFilesAreClean`.
5. **No forbidden data leaves the server.** Pinned by B5 `TestNoSecretsInResponses`, which seeds every kind of secret and content and scans every `GET` answer.
6. **Every change is audited, with IDs only.** Pinned by I1 `TestEveryChangeIsAudited` (table over every state-changing endpoint and CLI command).
7. **Operator revoke and delete behave like the user's own.** Pinned by B3 `TestAdminRevokeDevice` (socket closed with 4003, other devices told) and `TestAdminDeleteAccount` (cascade, typed confirmation).

---

## Parallel execution

```
Stage 0  Foundation (1 agent)       X1                      branch feat/admin-dashboard
Stage 1  Three lanes (3 agents)     A1–A4 | B1–B5 | C1–C4    each in its own worktree + branch
Stage 2  Merge + integration (1)    merge A, B, C → I1 → final review
```

- **Agents:** every coding agent runs on Sonnet 5.5 (`model: "sonnet"`). The final whole-branch review in I1 uses the most capable model.
- **Stage 0** must be committed on `feat/admin-dashboard` before any lane starts. Its stubs fix every file, route, type and signature, so a lane only fills files it owns. If a lane needs a change elsewhere, it records a `Ruling:` in its ledger and makes the smallest additive change; the merge step reconciles it.
- **Stage 1:** each lane runs `git worktree add ../myApp-admin-<x> -b admin/lane-<x> feat/admin-dashboard` and edits only its files:

  | Lane | Files it owns | Tasks |
  |---|---|---|
  | A Auth, audit, CLI | `internal/admin/{tokens,cli,auth,guard,api_audit}.go` and their `_test.go` files | 4 |
  | B Data API, module hooks | `internal/admin/{api_overview,api_invites,api_accounts,api_reports,api_server,mask}.go` and tests; `identity/admin.go`, `identity/invites.go`, `identity/devices.go`, `identity/me.go`; `social/admin.go`; `realtime/stats.go`; `push/stats.go`, `push/module.go`; `turn/status.go`; `serverinfo/document.go`, `serverinfo/module.go`; migrations `1004`, `5002` | 5 |
  | C Static UI, docs | `internal/admin/web/**`, `internal/admin/static.go`, `static_test.go`; `docs/server/admin-guide.md`; `deploy/.env.example`, `deploy/compose.yaml` (comments only); server design §1 and §5 (one line each) | 4 |

  Each lane keeps its ledger at `.superpowers/sdd/2026-10-28-admin-dashboard-lane-<x>/progress.md`.
- **Stage 2:** merge into `feat/admin-dashboard` in the order C, A, B, running `go test ./...` after each, then do I1. No file is owned by two lanes, so the merges should be clean.
- **CI budget:** these lanes touch only `server/` and `docs/`, so no Mac runner jobs start.

---

## Stage 0 — Foundation

### Task X1: Package skeleton, migration, wiring

**Files:**
- Create: `server/internal/db/migrations/6001_admin.sql` (D§8, exactly)
- Create: `server/internal/admin/module.go`, `routes.go`, `audit.go`, `admin_test.go`
- Create stubs (each handler returns `httpx.Err("internal", "not built")`, each guard passes through): `tokens.go`, `cli.go`, `auth.go`, `guard.go`, `api_overview.go`, `api_invites.go`, `api_accounts.go`, `api_reports.go`, `api_server.go`, `api_audit.go`, `mask.go`, `static.go` (serves only `web/index.html` at `/admin/` until C1), `web/index.html` (a placeholder page, so `//go:embed web` compiles)
- Create stubs: `identity/admin.go`, `social/admin.go`, `realtime/stats.go`, `push/stats.go`, `turn/status.go`, `serverinfo/document.go`
- Modify: `server/internal/app/app.go`, `app_test.go`; `server/internal/httpx/json.go`; `server/internal/config/config.go`, `config_test.go`

**Interfaces — Produces:**
- `admin`:
  - `type Session struct{ TokenID, Label string }`
  - `type apiFunc func(w http.ResponseWriter, r *http.Request, s Session) error`
  - `type Sources struct { Started time.Time; Live interface{ ConnectionCount() int }; Push interface{ Stats() push.Stats }; Relays interface{ Relays() []turn.RelayStatus }; Identity Identity; Reports interface{ MarkReportHandled(ctx context.Context, id, tokenID string) error }; Info interface{ Document(ctx context.Context) ([]byte, error) } }`
  - `type Identity interface { CreateInviteID(ctx context.Context, uses int, ttl time.Duration) (id, code string, err error); RevokeInvite(ctx context.Context, id string) error; AdminRevokeDevice(ctx context.Context, deviceID string) error; AdminDeleteAccount(ctx context.Context, accountID string) error }`
  - `func New(d core.Deps, src Sources) *Module`
  - `func (m *Module) Handler() http.Handler`: the full chain `hostGuard → secureHeaders → (static | api: requireSession → checkCSRF → handler)`; `POST /admin/api/login` skips `requireSession` and the CSRF token, not the header checks
  - `func (m *Module) testHandler(s Session) http.Handler` (unexported, for tests): the same routes, every request runs as `s`, with no host, session or CSRF checks
  - `func (m *Module) Commands() map[string]core.Command`: keys `"admin token create"` and `"admin sessions end"`
  - `func (m *Module) Run(ctx context.Context)`: hourly cleanup (stub until A2)
  - `type Entry struct{ Actor, TokenID, Action, Target string; Detail map[string]any }`
  - `func (m *Module) audit(ctx context.Context, q interface{ Exec(context.Context, string, ...any) (pgconn.CommandTag, error) }, e Entry) error`: inserts one `admin_audit` row at `m.d.Now()`; `q` is the pool or a transaction
- `routes.go` registers every path of D§7 to its stub, with Go 1.22 patterns, e.g. `"POST /admin/api/accounts/{id}/devices/{deviceID}/revoke"`.
- `httpx.WriteError(w http.ResponseWriter, err error)`: what `Router.fail` does (an `*Error` as is, anything else as `500 internal`, text never sent or logged)
- `config.Config.AdminHosts []string` from `APP_ADMIN_HOSTS` (comma list; entries lowercased, trimmed, empty dropped)
- Stub methods, filled by lane B: `(*identity.Module).CreateInviteID`, `RevokeInvite`, `AdminRevokeDevice`, `AdminDeleteAccount`; `(*social.Module).MarkReportHandled`; `(*realtime.Module).ConnectionCount() int`; `type push.Stats struct{ Enabled bool; Sent, Failed uint64 }` and `(*push.Module).Stats() Stats`; `type turn.RelayStatus struct{ Host, Region string; Healthy bool; RTT time.Duration }` and `(*turn.Module).Relays() []RelayStatus`; `(*serverinfo.Module).Document(ctx context.Context) ([]byte, error)`
- `app`:
  - `New` builds `admin.New(d, admin.Sources{Started: d.Now(), …})` from the other modules, after them, and adds it to `modules` (so its `Commands` and `Run` are picked up)
  - `AdminHandler` keeps `/healthz`, `/readyz`, `/debug/vars` unchanged, mounts `/admin/` on the admin module's `Handler()`, and redirects `GET /{$}` to `/admin/` (302)
  - `Command` tries three words, then two, then one

- [ ] **Step 1: Failing tests.**
  - `app_test.go`:
    - `TestAdminNotOnPublicHandler`: `GET /admin/`, `GET /admin/api/session` and `POST /admin/api/login` on `Handler()` → 404 `not_found` (the public router's envelope).
    - `TestAdminHasNoPublicRoutes`: `_, ok := any(adminModule).(interface{ Routes(*httpx.Router) })` is `false`.
    - `TestAdminMounted`: `GET /admin/` on `AdminHandler()` with `Host: 127.0.0.1:9090` → 200; `GET /` → 302 to `/admin/`; `/healthz` still 200 with no cookie.
    - `TestThreeWordCommand`: `Command(ctx, []string{"admin","token","create"}, …)` reaches the admin stub (it answers "not built"), while `"invite create"` still works.
  - `config_test.go` `TestAdminHosts`: `" Admin.Example.org ,,x.org"` → `["admin.example.org","x.org"]`; unset → empty.
  - `admin_test.go` `TestAuditWrites`: `audit(ctx, pool, Entry{Actor:"cli", Action:"token.create", Target:id, Detail:{"label":"x"}})` → one row with those values and `at` equal to the fake clock.
  - `admin_test.go` `TestMigrationTables`: the three tables of D§8 exist with the listed columns.
- [ ] **Step 2:** `go test ./internal/app/ ./internal/admin/ ./internal/config/` → FAIL.
- [ ] **Step 3: Implement** the files above. Stubs carry `// Lane A`, `// Lane B` or `// Lane C` comments naming the task that fills them.
- [ ] **Step 4:** `go vet ./... && go test ./...` → PASS (every earlier test still passes).
- [ ] **Step 5: Commit** `feat(admin): package skeleton, audit table and admin listener wiring`.

---

## Lane A — Auth, audit, CLI

### Task A1: Admin tokens and CLI commands

**Files:**
- Modify: `server/internal/admin/tokens.go`, `cli.go`
- Test: `server/internal/admin/tokens_test.go`

**Interfaces — Produces:**
- `func (m *Module) createToken(ctx context.Context, label string, ttl time.Duration) (id, token string, err error)`: token `adm_` + 32 random bytes base64url (no padding); stores `sha256(token)`; writes audit `token.create` (actor `cli`, target id, detail `{label}`) in the same transaction
- `func (m *Module) redeemToken(ctx context.Context, tx pgx.Tx, token string) (id, label string, err error)`: `update admin_tokens set used_at = $now where token_hash = $1 and used_at is null and expires_at > $now returning id, label`; no row → `core.ErrNotFound`
- `admin token create [--label TEXT] [--minutes N]`: prints the token and a newline, nothing else; `--minutes` default 15, 1–60; a label over 64 characters or with control characters is an error
- `admin sessions end`: deletes every `admin_sessions` row, prints `ended N sessions`, audits `sessions.end` with `{count}`

- [ ] **Step 1: Failing tests.**
  - `TestTokenCreatePrintsOnce`: output matches `^adm_[A-Za-z0-9_-]{43}\n$`.
  - `TestTokenStoredHashed`: the stored `token_hash` equals `sha256(token)`, and no column of `admin_tokens` contains the token text.
  - `TestTokenFlags`: `--minutes 0`, `--minutes 61`, and a 65-character label are errors; `--minutes 60` sets `expires_at` 60 minutes after the fake clock.
  - `TestRedeemToken`: redeems once; the second call, an expired token (clock advanced 16 minutes) and an unknown token each give `core.ErrNotFound`.
  - `TestSessionsEnd`: insert two session rows; the command prints `ended 2 sessions`, the table is empty, and one `sessions.end` audit row has `{"count":2}`.
  - `TestTokenCreateAudited`: one `token.create` row, actor `cli`, target the token ID.
- [ ] **Step 2:** `go test ./internal/admin/ -run 'Token|Redeem|SessionsEnd'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): one-time admin tokens and CLI commands`.

### Task A2: Login, sessions, logout, cleanup

**Files:**
- Modify: `server/internal/admin/auth.go`
- Test: `server/internal/admin/auth_test.go`

**Interfaces — Produces:**
- `POST /admin/api/login`, `POST /admin/api/logout`, `GET /admin/api/session` (D§3, D§7)
- `func (m *Module) requireSession(h apiFunc) http.HandlerFunc`: reads the `admin_session` cookie, looks up `sha256(cookie)`; missing, unknown, idle over 15 minutes, or past `expires_at` → `401 unauthorized` and the row (if any) is deleted; otherwise updates `last_seen_at` and calls `h` with the `Session`
- `func (m *Module) cookieSecure(r *http.Request) bool`: `false` only when `r.Host` without port is `localhost`, `127.0.0.1` or `[::1]`
- `Run`: every hour deletes sessions past idle or absolute time, unused tokens past `expires_at`, and audit rows older than 365 days

- [ ] **Step 1: Failing tests** (through `m.Handler()` with `Host: localhost:9090`, `Content-Type: application/json`, and a valid `Origin`):
  - `TestLoginSetsCookie`: a fresh token → 204; `Set-Cookie` has `admin_session=`, `Path=/admin`, `HttpOnly`, `SameSite=Strict`, no `Secure`, no `Max-Age`; `GET /admin/api/session` with it → `{tokenID, label, csrf, idleTimeoutSeconds:900, expiresAt}` where `expiresAt` is 8 hours after the clock.
  - `TestCookieSecureWhenRemote`: with `APP_ADMIN_HOSTS=admin.example.org` and that `Host`, the cookie has `Secure`.
  - `TestTokenWorksOnce`: the second login with the same token → 401.
  - `TestLoginFailuresLookAlike`: an unknown, a used, and an expired token give byte-identical 401 bodies; each writes one `login.failed` audit row with empty target and empty detail.
  - `TestLoginRateLimited`: the 6th try within a minute from one address → 429 with `Retry-After`; after 31 tries in an hour from changing addresses → 429.
  - `TestSessionIdleTimeout`: advance 14 minutes, request → 200; advance 16 minutes → 401, and the row is gone.
  - `TestSessionAbsoluteLifetime`: a request every 10 minutes keeps it alive until 8 hours, then 401.
  - `TestLogout`: 204, the cookie is cleared (`Max-Age=0`), the row is gone, a `logout` audit row exists; the old cookie → 401.
  - `TestCleanup`: one `Run` round deletes an idle session, an expired unused token, and a 366-day-old audit row, and keeps a used token and a 364-day-old audit row.
- [ ] **Step 2:** `go test ./internal/admin/ -run 'Login|Cookie|Session|Logout|Cleanup|TokenWorks'` → FAIL.
- [ ] **Step 3: Implement.** Login uses one transaction for `redeemToken`, the session insert and the `login` audit row. The rate limiters are `ratelimit.New(5, time.Minute, d.Now)` keyed by `httpx.ClientIP(r, d.Config.TrustProxy)` and `ratelimit.New(30, time.Hour, d.Now)` keyed by `"all"`.
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): login, sessions and logout`.

### Task A3: Host guard, CSRF, security headers

**Files:**
- Modify: `server/internal/admin/guard.go`
- Test: `server/internal/admin/guard_test.go`

**Interfaces — Produces:**
- `func (m *Module) hostGuard(next http.Handler) http.Handler`: D§2; a wrong `Host` → `421` with an empty body
- `func secureHeaders(next http.Handler) http.Handler`: the headers of D§4, exactly; `Cache-Control: no-store` for `/admin/api/`, `no-cache` for static files
- `func (m *Module) checkCSRF(r *http.Request, s Session, needToken bool) error`: for any method but `GET` and `HEAD`: `Content-Type` must be `application/json` (parameters allowed), `Sec-Fetch-Site` if present must be `same-origin`, `Origin` if present must equal `http://` or `https://` plus `r.Host`; and when `needToken`, `X-CSRF-Token` must match the session's `csrf_hash` (constant-time compare of the SHA-256). Any failure → `403 forbidden`

- [ ] **Step 1: Failing tests.**
  - `TestHostGuard` (table): `localhost:9090`, `127.0.0.1:9090`, `[::1]:9090` pass; `evil.example`, `localhost.evil.example`, an empty host → 421; `admin.example.org` passes only when in `APP_ADMIN_HOSTS`.
  - `TestSecurityHeaders`: for `/admin/`, `/admin/api/session`, a 401 from `/admin/api/overview` and a 404 from `/admin/nope.html`, every header of D§4 is present with the exact value; `Cache-Control` per path type. (Lane C's static files get the same check in I1.)
  - `TestStateChangeNeedsCSRF` (table, against `POST /admin/api/invites` with a logged-in session): no token, a wrong token, another session's token, `Content-Type: text/plain`, `application/x-www-form-urlencoded`, `Sec-Fetch-Site: cross-site`, `Origin: http://evil.example` → 403 and no invite row; the right token with good headers → passes the guard.
  - `TestLoginChecksHeadersNotToken`: login with `Origin: http://evil.example` → 403; with no CSRF token but good headers → works.
  - `TestGetNeedsNoCSRF`: `GET /admin/api/overview` with only the cookie → passes the guard.
- [ ] **Step 2:** `go test ./internal/admin/ -run 'Host|Headers|CSRF|NeedsNo'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): host guard, CSRF checks and security headers`.

### Task A4: Audit log API

**Files:**
- Modify: `server/internal/admin/api_audit.go`
- Test: `server/internal/admin/api_audit_test.go`

**Interfaces — Produces:**
- `GET /admin/api/audit?action=&after=` → D§7; newest first by `(at, id)`; 50 per page; `next` is the last row's `id` as a decimal string; `action` must be one of D§8's actions or empty (else `bad_request`); `tokenLabel` is joined from `admin_tokens` (empty if the token is gone)

- [ ] **Step 1: Failing tests** (through `testHandler`):
  - `TestAuditList`: 120 rows → pages of 50, 50, 20; `next` is `null` on the last; order newest first.
  - `TestAuditFilter`: `action=invite.revoke` returns only those; `action=nope` → `bad_request`.
  - `TestAuditLabel`: a row for a token with label `laptop` shows `tokenLabel:"laptop"`; a CLI row shows `actor:"cli"` and `tokenID:null`.
- [ ] **Step 2:** `go test ./internal/admin/ -run Audit` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): audit log API`.

---

## Lane B — Data API and module hooks

Every lane B test runs through `m.testHandler(admin.Session{TokenID: <id>, Label: "test"})`, with `testkit.NewDeps` and the `testkit.Account` and `testkit.Device` fixtures.

### Task B1: Overview and live numbers

**Files:**
- Modify: `server/internal/realtime/stats.go`, `server/internal/push/stats.go`, `server/internal/push/module.go` (count in `Push`), `server/internal/turn/status.go`, `server/internal/admin/api_overview.go`
- Test: `server/internal/realtime/stats_test.go`, `server/internal/push/stats_test.go`, `server/internal/turn/status_test.go`, `server/internal/admin/api_overview_test.go`

**Interfaces — Produces:**
- `ConnectionCount()`: number of connections in the hub
- `push.Stats()`: `Enabled` is `PushGatewayURL != ""`; `Sent` counts gateway answers 2xx; `Failed` counts every other outcome of a send attempt (request error, non-2xx, including 410); a device with no handle counts as neither. Counters are `atomic.Uint64`
- `turn.Relays()`: every configured relay in `APP_RELAYS` order, with the prober's current state; `RTT` is 0 until a probe has succeeded
- `GET /admin/api/overview` → D§6.1 and D§7; version from `debug.ReadBuildInfo` settings `vcs.revision` (first 12 characters) and `vcs.time`, else `"unknown"`; `database` is `"down"` when a 2 s ping fails, and then the counts are `null`

- [ ] **Step 1: Failing tests.**
  - `TestConnectionCount`: two devices connect over `/v1/ws` → 2; one closes → 1.
  - `TestPushStats`: a fake gateway answering 200, then 500, then 410 → `Sent 1, Failed 2`; no handle → unchanged; no gateway URL → `Enabled:false`.
  - `TestRelayStatus`: one relay answering the STUN test server, one not answering three times → healthy and unhealthy, in config order.
  - `TestOverview`: two accounts, three devices, one conversation, two `client_messages` rows today and one yesterday (fake clock) → `counts {accounts:2, devices:3, conversations:1, messagesToday:2}`; `connections`, `push` and `calls` come from fake `Sources`; `uptimeSeconds` follows the clock.
  - `TestOverviewDatabaseDown`: a closed pool → 200 with `database:"down"` and null counts.
- [ ] **Step 2:** `go test ./internal/realtime/ ./internal/push/ ./internal/turn/ ./internal/admin/ -run 'ConnectionCount|PushStats|RelayStatus|Overview'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): overview with live connection, push and relay numbers`.

### Task B2: Invites

**Files:**
- Create: `server/internal/db/migrations/1004_invite_admin.sql` (D§9)
- Modify: `server/internal/identity/invites.go` (`CreateInvite` calls `CreateInviteID`), `server/internal/identity/admin.go`, `server/internal/admin/api_invites.go`
- Test: `server/internal/identity/invites_test.go` (add), `server/internal/admin/api_invites_test.go`

**Interfaces — Produces:**
- `CreateInviteID(ctx, uses, ttl) (id, code string, err error)`; `CreateInvite` keeps its signature and behavior
- `RevokeInvite(ctx, id)`: `update invites set uses_left = 0, revoked_at = $now where id = $1 and revoked_at is null`; unknown id → `core.ErrNotFound`; already revoked → `nil`
- Invite `state`: `revoked` if `revoked_at` is set, else `expired` if `expires_at` ≤ now, else `used up` if `uses_left` = 0, else `active`. `?state=` takes `active` (default) or `all`
- `GET /admin/api/invites`, `POST /admin/api/invites` (`uses` 1–100, `days` 1–90, defaults 1 and 14; `link` = `https://<APP_HOST>/invite/<code>`), `POST /admin/api/invites/{id}/revoke`, with audit `invite.create {uses, days}` and `invite.revoke`

- [ ] **Step 1: Failing tests.**
  - `TestRevokeInvite`: a revoked invite fails `POST /v1/invites/check` (`valid:false`) and sign-up start (`invite_invalid`); revoking twice is fine; an unknown id → `ErrNotFound`.
  - `TestInviteCreateCommandUnchanged`: `invite create` output is still `https://<host>/invite/XXXXX-XXXXX`.
  - `TestAdminInviteCreate`: `{uses:3, days:7}` → the full code once, and a row with `uses_left 3`; `{uses:0}`, `{uses:101}`, `{days:91}` → `bad_request`; one audit row.
  - `TestAdminInviteList`: rows show `codePrefix` as the first five characters plus `-…`, and no field anywhere in the body contains the full code; states as above; `state=all` includes revoked and expired.
  - `TestAdminInviteRevoke`: 204, audit row; unknown → `not_found`.
- [ ] **Step 2:** `go test ./internal/identity/ ./internal/admin/ -run Invite` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): list, create and revoke invites`.

### Task B3: Accounts, devices, deletion

**Files:**
- Modify: `server/internal/identity/admin.go`, `server/internal/identity/devices.go`, `server/internal/identity/me.go` (the handlers call the shared functions), `server/internal/admin/mask.go`, `server/internal/admin/api_accounts.go`
- Test: `server/internal/identity/admin_test.go`, `server/internal/admin/mask_test.go`, `server/internal/admin/api_accounts_test.go`

**Interfaces — Produces:**
- `identity`: an unexported `revokeDevice(ctx, accountID, deviceID string) error` and `deleteAccount(ctx, accountID string) error` hold today's handler bodies (same SQL, same locking order, same `Disconnect` and `device.revoked` notices); the user handlers and `AdminRevokeDevice(ctx, deviceID)` (looks up the account first) and `AdminDeleteAccount(ctx, accountID)` both call them
- `func maskEmail(email string) string`: D§5; an address without `@` becomes `•••`
- `GET /admin/api/accounts?q=&after=` (prefix match on `username`, `q` must match `^[a-z0-9_]{1,32}$`, ordered by username, 50 per page), `GET /admin/api/accounts/{id}` (audit `account.view`), `POST /admin/api/accounts/{id}/devices/{deviceID}/revoke` (audit `device.revoke {accountID}`), `POST /admin/api/accounts/{id}/delete` (audit `account.delete {reason}`)

- [ ] **Step 1: Failing tests.**
  - `TestUserPathsUnchanged`: the existing `TestRevokeDevice` and `TestDeleteAccount` in `identity` still pass (run them, do not edit them).
  - `TestAdminRevokeDevice`: bob has an iPhone and a Mac; revoking the Mac → `Fakes.Live` records `(Mac, 4003)`, `Fakes.Notify` has `device.revoked {deviceID}` for the iPhone, and the Mac's session row is gone; a device of another account under this account's path → `not_found`, nothing deleted.
  - `TestAdminDeleteAccount`: `{confirmUsername:"bob", reason:"abuse"}` → 204; bob's devices, sessions, KeyPackages, push tokens and blocks are gone (count rows); `confirmUsername:"Bob"`, `"bo"`, or `reason:"x"` → `bad_request` and nothing is deleted.
  - `TestMaskEmail` (table): `ana@example.org` → `a•••@example.org`; `a@x.org` → `a•••@x.org`; `x` → `•••`.
  - `TestAccountSearch`: `q=an` finds `ana` and `anton`, not `bob`; `q=AN` and `q=` → `bad_request`; every `email` in the body is masked and the full address appears nowhere in it.
  - `TestAccountView`: devices with name, platform, created and last seen; one `account.view` audit row whose target is the account ID and whose detail is empty.
- [ ] **Step 2:** `go test ./internal/identity/ ./internal/admin/ -run 'Admin|Mask|Account|UserPaths'` → FAIL.
- [ ] **Step 3: Implement.** Move code, do not copy it: the user and admin paths must share one function each.
- [ ] **Step 4:** same command, then `go test ./internal/identity/` → PASS.
- [ ] **Step 5: Commit** `feat(admin): account search, device revocation and account deletion`.

### Task B4: Reports

**Files:**
- Create: `server/internal/db/migrations/5002_report_handled.sql` (D§9)
- Modify: `server/internal/social/admin.go`, `server/internal/admin/api_reports.go`
- Test: `server/internal/social/admin_test.go`, `server/internal/admin/api_reports_test.go`

**Interfaces — Produces:**
- `MarkReportHandled(ctx, id, tokenID)`: sets `handled_at = $now, handled_by = $tokenID` where `handled_at is null`; unknown id → `core.ErrNotFound`; already handled → `nil`, unchanged
- `GET /admin/api/reports?state=open|handled|all&after=` (newest first, 50 per page), `GET /admin/api/reports/{id}` (audit `report.view`), `POST /admin/api/reports/{id}/handle` (audit `report.handle` only when it changed something)
- `Report` as D§7. `messageCount` is `jsonb_array_length(messages)`, `hasNote` is `note <> ''`. **The SQL never selects `messages` or `note`.**

- [ ] **Step 1: Failing tests.**
  - `TestMarkReportHandled`: sets both columns once; the second call leaves `handled_at` unchanged.
  - `TestReportList`: open, handled and all filters; a deleted reported account shows `reportedUsername:null`; a reporter whose account is gone shows `reporterAccountID:null`.
  - `TestReportHidesText`: a report whose messages and note contain `SECRET-TEXT` → neither list nor detail body contains it.
  - `TestReportHandleAudited`: one `report.handle` row on the first call, none on the second.
- [ ] **Step 2:** `go test ./internal/social/ ./internal/admin/ -run Report` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): report list, detail and mark handled`.
- [ ] **Step 6 (only if the owner approves O-2):** add `messages` and `note` to the detail answer only, with a test that the list still omits them. Commit `feat(admin): show shared report text on the report page`.

### Task B5: Server settings and the leak check

**Files:**
- Modify: `server/internal/serverinfo/document.go`, `server/internal/serverinfo/module.go` (`info` writes `Document`'s bytes), `server/internal/admin/api_server.go`
- Test: `server/internal/serverinfo/document_test.go`, `server/internal/admin/api_server_test.go`, `server/internal/admin/leak_test.go`

**Interfaces — Produces:**
- `Document(ctx)`: the exact bytes `GET /v1/server` sends
- `GET /admin/api/server` → `{policy, info, settings}`; `settings = {host, listen, adminListen, adminHosts, relays:[{host, region}], pushGateway, pushConfigured, escrowConfigured, smtpConfigured, docsConfigured, trustProxy, labMode}`; booleans only for secrets and key files

- [ ] **Step 1: Failing tests.**
  - `TestDocumentMatchesEndpoint`: `Document` equals the body of `GET /v1/server`.
  - `TestServerSettings`: with `APP_SMTP_URL=smtp://u:PASSWORD@h`, `APP_TURN_SECRET=TURNSECRET`, a database URL containing `DBPASS`, and key file paths under `/secrets/`: the body has `smtpConfigured:true` and contains none of `PASSWORD`, `TURNSECRET`, `DBPASS`, `/secrets/`.
  - `TestNoSecretsInResponses`: seed an account with email `leak@example.org`, a session token, a push token whose sealed bytes are known, an escrow row, a report with text `SECRET-TEXT`, an invite, an admin token, and a request with `RemoteAddr 203.0.113.7`. Call every `GET` endpoint of D§7 (with IDs from the seed). No body contains: the full email, the session token or its hash in hex or base64, the sealed push bytes in base64 or base64url, the escrow bytes, `SECRET-TEXT`, the full invite code, the admin token or its hash, `203.0.113`.
- [ ] **Step 2:** `go test ./internal/serverinfo/ ./internal/admin/ -run 'Document|ServerSettings|NoSecrets'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** same command, then `go test ./...` → PASS.
- [ ] **Step 5: Commit** `feat(admin): read-only server settings and leak check`.

---

## Lane C — Static UI and docs

Lane C writes against the JSON shapes in D§7 and does not need lane B's code. To try pages by hand, run `appserver` locally with lab data and open `http://localhost:9090/admin/` (after merge), or serve `testHandler` from a throwaway test.

### Task C1: Static serving, shell, login page, static checks

**Files:**
- Modify: `server/internal/admin/static.go`
- Create: `server/internal/admin/web/index.html`, `login.html`, `web/static/admin.css`, `web/static/common.js`, `web/static/login.js`, `web/static/overview.js` (empty module for now)
- Test: `server/internal/admin/static_test.go`

**Interfaces — Produces:**
- `func (m *Module) staticHandler() http.Handler`: serves `GET /admin/` as `web/index.html`, `GET /admin/{page}.html` and `GET /admin/static/{file}` from the embedded FS only; any other path → 404; content types `text/html; charset=utf-8`, `text/css; charset=utf-8`, `text/javascript; charset=utf-8`; no directory listings
- `common.js` (loaded with `<script type="module" src>`): `api(method, path, body)` (sends `X-CSRF-Token` and JSON; on 401 goes to `login.html`), `el(tag, attrs, ...children)` (builds nodes; text via `textContent`; refuses `on*`, `style` and `innerHTML` keys), `confirmDialog(text, typed?)`, `announce(text)` (the `aria-live` region), `formatTime(iso)`, the nav bar and the "Skip to content" link
- Every page: `<html lang="en">`, `<meta name="color-scheme" content="light dark">`, `<link rel="stylesheet" href="static/admin.css">`, one shared header and nav (Overview, Invites, Accounts, Reports, Server, Audit log, Sign out), `<main id="main">`, a `<noscript>` line "This page needs JavaScript."
- `admin.css`: system fonts, CSS custom properties for colors with a `@media (prefers-color-scheme: dark)` set, visible `:focus-visible` outline, tables that scroll sideways on narrow screens

- [ ] **Step 1: Failing tests** in `static_test.go` (the "headless check"; pure Go, no browser):
  - `TestStaticFilesAreClean`: for every embedded `.html` file: no `<script` without `src=`, no attribute starting with `on`, no `style=` attribute, no `<style`, no `javascript:`, no `http://` or `https://` in any `src` or `href`. For every `.js` file: none of `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval(`, `new Function`, `http://`, `https://`, `import(` with a non-relative path. For `.css`: no `@import` and no `url(http`.
  - `TestEveryAssetEmbedded`: every `src` and `href` in every HTML file, and every relative `import` in every JS file, names a file present in the embedded FS; and every embedded file is referenced by at least one page (no dead files).
  - `TestStaticServing` (through `m.Handler()` with `Host: localhost`): each page and asset → 200 with its content type; `/admin/../go.mod`, `/admin/static/`, `/admin/nope.html` → 404. (The headers come from lane A's `secureHeaders`; I1 checks them on every file.)
  - `TestPagesAccessible`: every HTML page has `lang`, one `<main id="main">`, a skip link to `#main`, a `<title>`, and every `<input>` has an `id` that a `<label for>` names.
- [ ] **Step 2:** `go test ./internal/admin/ -run 'Static|Asset|Pages'` → FAIL.
- [ ] **Step 3: Implement.** The login page has one password-type field "Admin token", a "Sign in" button, and shows "That token is not valid. Create a new one with `appserver admin token create`." on 401 and "Too many tries. Wait a minute." on 429.
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5: Commit** `feat(admin): embedded static pages, login page and static checks`.

### Task C2: Overview, invites, server and audit pages

**Files:**
- Modify: `web/index.html`, `web/static/overview.js`
- Create: `web/invites.html`, `web/server.html`, `web/audit.html`, `web/static/invites.js`, `web/static/server.js`, `web/static/audit.js`
- Test: `static_test.go` gains nothing new; its existing tests must keep passing for the new files

- [ ] **Step 1: Failing test:** add the new pages to `TestPagesAccessible`'s expected list (`index`, `login`, `invites`, `server`, `audit`) → FAIL (files missing).
- [ ] **Step 2: Implement.**
  - Overview: a definition list or cards for health, version, uptime, and every count of D§6.1, with labels "Accounts", "Devices", "Conversations", "Messages today", "Live connections", "Pushes sent", "Pushes failed", "Call relays"; each relay as a row with "Working" or "Not answering" in text, not color alone; refresh every 30 s while `document.visibilityState` is `visible`.
  - Invites: a table (Code, State, Uses left, Created, Expires, action) with "Show all" toggle and "Load more"; a form (Uses, Days, "Create invite"); after create, the link in a read-only field with a "Copy link" button and the note "Shown once. Copy it now."; "Revoke" asks through `confirmDialog`.
  - Server: policy and info as definition lists (not raw JSON), settings with "On"/"Off"; a lab-mode banner with the admin guide's warning text.
  - Audit log: a table (Time, Who, Action, Target) with an action filter `<select>` and "Load more".
- [ ] **Step 3:** `go test ./internal/admin/ -run 'Static|Asset|Pages'` → PASS.
- [ ] **Step 4: Commit** `feat(admin): overview, invites, server and audit pages`.

### Task C3: Accounts and reports pages

**Files:**
- Create: `web/accounts.html`, `web/account.html`, `web/reports.html`, `web/report.html`, and `web/static/accounts.js`, `account.js`, `reports.js`, `report.js`

- [ ] **Step 1: Failing test:** add the four pages to `TestPagesAccessible`'s list → FAIL.
- [ ] **Step 2: Implement.**
  - Accounts: a search field "Username starts with" and a results table (Username, Display name, Email, Created, Devices) linking to `account.html?id=…`.
  - Account: the fields and a devices table (Name, Type, Added, Last seen, action "Sign out this device", which revokes after `confirmDialog`). "Delete account" opens a dialog that asks for a reason (`<select>`: "The user asked", "Abuse", "Other") and enables its button only when the typed name equals the username; after deletion, go back to the search with the notice "Account deleted."
  - Reports: a table (Reported, Reason, Reported account, Reporter, Shared messages, Note, State) with filter Open/Handled/All, linking to `report.html?id=…`.
  - Report: the metadata and a "Mark handled" button. Show "Shared messages: N" and "Note: yes/no" only; leave a single `// O-2` comment where the text would go.
  - Words: "Sign out this device" (not "revoke"), "Delete account", "Mark handled", "deleted account" for a null username.
- [ ] **Step 3:** `go test ./internal/admin/ -run 'Static|Asset|Pages'` → PASS.
- [ ] **Step 4: Commit** `feat(admin): accounts and reports pages`.

### Task C4: Admin guide and design pointers

**Files:**
- Modify: `docs/server/admin-guide.md`, `docs/superpowers/specs/2026-10-27-server-design.md` (§1 and §5 only), `deploy/.env.example`, `deploy/compose.yaml` (comment on the 9090 line only)

- [ ] **Step 1: Write.**
  - Admin guide, new section "Admin dashboard", after "Monitoring":
    - what it is (D§6 in one list) and what it never shows (D§5 in one list);
    - how to reach it: `ssh -N -L 9090:127.0.0.1:9090 you@<host>`, then `http://localhost:9090/admin/`;
    - how to sign in: `docker compose exec appserver /appserver admin token create --label laptop`, paste once; idle 15 minutes, at most 8 hours; `admin sessions end` for a lost laptop;
    - "Never publish port 9090", repeated; `APP_ADMIN_HOSTS` only behind your own HTTPS proxy with its own access control;
    - every action is in the audit log, kept 365 days; escrow export stays a CLI command.
  - Admin guide "Monitoring": `/debug/vars` shows the Go runtime's numbers; the dashboard's overview shows the server's own.
  - Server design: in §1 "Out", replace "an admin web UI" with a pointer to the dashboard design; in §5's `APP_ADMIN_LISTEN` row add "and the admin dashboard (`/admin/`)", and add an `APP_ADMIN_HOSTS` row.
  - `.env.example`: a commented `# APP_ADMIN_HOSTS=` line with one sentence.
  - `compose.yaml`: the 9090 comment says `/healthz /readyz /debug/vars /admin, never public`.
- [ ] **Step 2:** `bash scripts/check-docs.sh` → PASS; `sh deploy/compose_test.sh` still passes if it checks the compose file.
- [ ] **Step 3: Commit** `docs(admin): admin dashboard in the admin guide`.

---

## Stage 2 — Integration

### Task I1: Merge lanes and test end to end

**Files:**
- Create: `server/internal/admin/e2e_test.go`

- [ ] **Step 1:** Merge C, A, B into `feat/admin-dashboard`, running `go vet ./... && go test ./...` after each. Expected: PASS after every merge.
- [ ] **Step 2: Failing end-to-end tests,** using `app.New` with real modules and the `testkit` fakes for mail and push, calling `AdminHandler()` with `Host: localhost:9090` like a browser (cookie jar, `Origin`, `Sec-Fetch-Site: same-origin`, CSRF token from `/admin/api/session`):
  - `TestDashboardFlow`: `Command("admin token create")` → login → `GET /admin/` → overview → create an invite → sign a user up with it through the public API → find them in account search → sign out their Mac → delete their account → audit log shows `token.create, login, invite.create, account.view, device.revoke, account.delete` in order, with targets that are IDs only.
  - `TestEveryChangeIsAudited` (table over `POST /invites`, `/invites/{id}/revoke`, `/accounts/{id}/devices/{id}/revoke`, `/accounts/{id}/delete`, `/reports/{id}/handle`, `/logout`, and both CLI commands): each adds exactly one audit row with the right action, actor, token ID and target, and no row's `target` or `detail` contains a username, email or invite code.
  - `TestHeadersOnEveryFile`: every embedded file, served through `AdminHandler()`, carries the D§4 headers with the exact CSP.
  - `TestDashboardDeadAfterSessionsEnd`: logged in, `admin sessions end` → the next API call is 401 and the page script sends the browser to `login.html` (check that the 401 body is the envelope).
- [ ] **Step 3:** `go test ./internal/admin/ -run 'Dashboard|EveryChange'` → FAIL only where the wiring is wrong; fix the wiring, not the lanes' behavior.
- [ ] **Step 4:** `go vet ./... && go test -race ./... && scripts/fuzz-smoke.sh && bash ../scripts/check-docs.sh` → all PASS. `git diff main -- server/go.mod server/go.sum` is empty.
- [ ] **Step 5: Manual look (optional, no new tool):** run the server against the test database, open the dashboard through `http://localhost:9090/admin/` in a browser, sign in, and check light and dark mode, keyboard-only use, and that the browser console shows no CSP violation.
- [ ] **Step 6: Commit** `test(admin): end-to-end dashboard flow and audit coverage`.
- [ ] **Step 7:** Whole-branch review (superpowers:requesting-code-review, on the most capable model) using this plan's Review Focus, the security review focus below, and every lane ledger's `Ruling:` lines.

## Security review focus

For the I1 reviewer, in this order:
1. **Exposure:** the admin handler is reachable only through `AdminHandler()`; the compose file still publishes 9090 on `127.0.0.1` only; `hostGuard` runs before everything under `/admin`, including static files and login.
2. **Tokens and sessions:** `crypto/rand` for every secret; only SHA-256 stored; one-time redemption is a single `update … returning` (no read-then-write race); cookie flags exactly as D§3; the idle and absolute limits are checked on every request; logout and `sessions end` delete rows, not just cookies.
3. **CSRF and browser:** every non-`GET` route passes `checkCSRF`; no route accepts form encoding; CSP is on every response, including errors and 404s; no inline script or style anywhere; JS never builds HTML from strings.
4. **Data minimization:** `TestNoSecretsInResponses` covers every `GET` route that exists after the merge (compare against `routes.go`); report SQL never selects `messages` or `note` (unless O-2); emails masked server-side, not in JS.
5. **Audit integrity:** every state change writes its row in the same transaction where possible; IDs only; `login.failed` stores nothing from the presented token; retention deletes only rows older than 365 days.
6. **Shared paths:** the operator's device revocation and account deletion run the same function as the user's, with the same lock order.
7. **Rate limiting and logging:** login limits keyed by `httpx.ClientIP` (IPv6 by /64); no address, token, cookie or email in any log line (grep the new code for `slog` calls and check every argument).

## Not in this plan

- Policy editing and live policy reload (owner decision O-3).
- Showing report text (owner decision O-2; B4 Step 6 is ready for it).
- Multiple named admin accounts, passkeys or two-factor login for the dashboard. The SSH tunnel plus one-time token is the model for now.
- Charts or history of the overview numbers; use `/debug/vars` and external monitoring.
- Email to a user whose account the operator deleted (owner decision O-5).
