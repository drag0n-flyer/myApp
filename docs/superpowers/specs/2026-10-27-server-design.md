# Go Server Design (Phase 1)

**Status:** draft for owner review. **Builds on:** [GOAL.md](../../../GOAL.md) §5–7, §11 Phase 1, §16. **Plan:** [2026-10-27-go-server.md](../plans/2026-10-27-go-server.md).

This document is the contract the five build lanes share. If code and this document disagree, this document wins until the owner changes it.

## 1. Scope

**In:** accounts (username, email for recovery), invite-only sign-up, passkey sign-in with email-code backup, devices, device linking and revocation, account deletion, the MLS Delivery Service for 1:1 conversations, offline inbox and sync, server-readable mode, encrypted attachments, user lookup and opt-in email discovery, blocking and reporting, WebSocket live events, 1:1 call signaling, TURN credentials with relay health checks, the push gateway (a separate binary) and the server's push sender, the server info document, Docker Compose deployment, and an admin CLI.

**Out (later phases):** federation, groups with more than two accounts, the SFU, S3 object storage (attachments use a local directory behind an interface), phone-number discovery, encrypted backups, multiple server nodes, an admin web UI (see the [admin dashboard design](2026-10-28-admin-dashboard-design.md)), and Android push.

## 2. Decisions I made — owner to confirm

These were not decided in GOAL.md. Each one changes what gets built.

1. **Server-readable mode uses an escrow copy.** In a server-readable conversation the client sends its normal MLS message plus an *escrow copy*: the plaintext sealed with HPKE to the server's escrow public key. The server never joins the MLS group and needs no MLS library. In a strict E2EE conversation the server rejects any escrow copy. Owner review: cryptography.
2. **The encryption mode is set once, at creation**, from the server policy `encryption.newConversations` (`"e2ee"` or `"server_readable"`). A later policy change affects only new conversations. Phase 1 has no transition API, so no conversation can be downgraded.
3. **Wire format v1 is JSON**, with binary fields as base64url without padding, and WebSocket per-message compression on. Revisit binary framing after Phase 0 data measurements.
4. **Three new Go dependencies** (all Apache-2.0-compatible): `github.com/jackc/pgx/v5` (MIT, Postgres driver), `github.com/coder/websocket` (ISC), and `github.com/go-webauthn/webauthn` (BSD-3, passkey verification; it brings its own transitive modules). Everything else uses the standard library, including HPKE (`crypto/hpke`, Go 1.26), Ed25519, ECDSA, HKDF and SMTP. **Approving this design approves these three.**
5. **Call state lives in memory** on the single server node. A restart ends ringing calls; calls that are already connected keep running, because media is peer-to-peer.
6. **Display names are stored on the server in plain text** (Phase 1). Encrypted profiles come later.
7. **TURN credentials last 12 hours.** coturn checks them on every allocation refresh, so the lifetime must cover the longest call. The TURN username is `<expiry>:<random 8 hex>`, which never contains an account or device ID.
8. **Go module path** `github.com/drag0n-flyer/myApp/server`. Confirmed against the repo `drag0n-flyer/myApp` (2026-10-27).

## 3. Components

One Go module in `server/` produces two binaries.

| Package (`server/internal/…`) | Responsibility | Lane |
|---|---|---|
| `config` | Environment config and policy file | F |
| `db` | Postgres pool, embedded migrations, the `Directory` implementation, test databases | F |
| `httpx` | Router, JSON helpers, error envelope, auth middleware, rate limiter, client IP | F |
| `core` | Shared types and interfaces (§4), test fakes in `core/coretest` | F |
| `mail` | SMTP `Mailer`, plus a capturing fake in `mail/mailtest` | F |
| `app` | Wires the modules into one `http.Handler`; admin CLI dispatch | F |
| `identity` | Accounts, invites, email codes, passkeys, sessions, devices, linking, deletion | A |
| `delivery` | KeyPackages, conversations, commits, messages, inbox, escrow | B |
| `realtime` | WebSocket hub and the `Notifier` (WebSocket first, push otherwise) | C |
| `calls` | Call state machine and signaling | C |
| `turn` | TURN credentials and relay health checks | C |
| `push` | Push token registry and sender to the gateway | D |
| `pushproto` | Sealed handles, padding, request signing (shared by `push` and `pushgw`) | D |
| `pushgw` | The push gateway: server registry, request signatures, sealed handles, APNs | D |
| `social` | User lookup, email discovery, profile settings, blocks, reports | E |
| `attachments` | Encrypted blob upload, download and expiry | E |
| `serverinfo` | `GET /v1/server`, `/invite/{code}` page, apple-app-site-association, assetlinks.json | E |

Binaries: `server/cmd/appserver` and `server/cmd/pushgw`. Deployment files go in `deploy/`.

## 4. Shared interfaces (package `core`)

```go
type Principal struct{ AccountID, DeviceID string }

type PushKind int
const (PushNone PushKind = iota; PushMessage; PushCall)

type Event struct {
    Type        string   // WebSocket frame type, e.g. "inbox", "call.ring"
    Data        any      // marshalled as the frame's "data"
    Push        PushKind // what to send if the device is offline
    PushPayload []byte   // opaque; padded by the push sender
}

type Notifier interface{ Notify(ctx context.Context, deviceID string, ev Event) }
type Pusher interface{ Push(ctx context.Context, deviceID string, kind PushKind, payload []byte) error }
type Blocklist interface{ IsBlocked(ctx context.Context, blocker, blocked string) (bool, error) }
type Directory interface {
    DeviceIDs(ctx context.Context, accountID string) ([]string, error)
    AccountOf(ctx context.Context, deviceID string) (string, error) // ErrNotFound if none
}
type SessionVerifier interface{ Verify(ctx context.Context, token string) (Principal, error) }
type Mailer interface{ Send(ctx context.Context, to, subject, body string) error }
type Disconnector interface{ Disconnect(deviceID string, code int, reason string) } // closes a live WebSocket
type Command func(ctx context.Context, args []string, out io.Writer) error         // admin CLI subcommand

var ErrNotFound = errors.New("not found")
func NewID() string // random UUIDv4, lowercase

type Deps struct {
    DB *pgxpool.Pool; Config config.Config; Policy config.Policy
    Log *slog.Logger; Now func() time.Time; Mail Mailer
    Directory Directory; Blocks Blocklist; Notify Notifier; Push Pusher; Live Disconnector
}
```

Every module has `func New(d core.Deps) *Module` and `func (m *Module) Routes(r *httpx.Router)`. A module may also have `Commands() map[string]core.Command` (keys like `"invite create"`) and `Run(ctx context.Context)` for background work; `app` starts every `Run` with the server.

## 5. Configuration

Environment variables (`APP_` prefix). Required ones are marked ★.

| Variable | Default | Meaning |
|---|---|---|
| `APP_HOST` ★ | — | Public host name, e.g. `chat.example.org`; also the WebAuthn RP ID |
| `APP_LISTEN` | `:8080` | Public HTTP listener (TLS is terminated by the reverse proxy) |
| `APP_ADMIN_LISTEN` | `127.0.0.1:9090` | `/healthz`, `/readyz`, `/debug/vars` and the admin dashboard (`/admin/`) |
| `APP_ADMIN_HOSTS` | empty | Comma list of extra `Host` names the admin listener accepts, for an operator's own HTTPS proxy |
| `APP_DATABASE_URL` ★ | — | Postgres URL |
| `APP_DATA_DIR` | `/var/lib/appserver` | Attachments directory root |
| `APP_POLICY_FILE` | — | JSON policy file; defaults below if unset |
| `APP_SMTP_URL` | — | `smtp://user:pass@host:587`; if unset, mail is logged as "not sent", never with the code |
| `APP_MAIL_FROM` | `no-reply@$APP_HOST` | Sender address |
| `APP_TURN_SECRET` | — | coturn `static-auth-secret`; calls are disabled if unset |
| `APP_RELAYS` | — | Comma list `host[@region]`, e.g. `turn1.example.org@eu,turn2.example.org@us` |
| `APP_PUSH_GATEWAY_URL` | — | e.g. `https://push.example.org`; push is disabled if unset |
| `APP_PUSH_KEY_FILE` | — | Ed25519 private key (PKCS#8 PEM) this server signs gateway requests with |
| `APP_PUSH_SERVER_ID` | — | ID the gateway gave this server at registration |
| `APP_ESCROW_KEY_FILE` | — | X25519 private key (PKCS#8 PEM); required only when the policy allows server-readable |
| `APP_APPLE_APP_IDS` | — | Comma list `TEAMID.bundle.id` for apple-app-site-association |
| `APP_ANDROID_APPS` | — | Comma list `package:<64 hex>` (the app's signing-certificate SHA-256) for `assetlinks.json` and Android passkey origins; empty accepts no Android origins |
| `APP_TRUST_PROXY` | `false` | Use the last `X-Forwarded-For` hop as the client IP |
| `APP_DOCS_DIR` | — | Operator documents served at `/.well-known/security.txt`, `/privacy`, `/terms` (plan 9) |

Policy file (`config.Policy`), with defaults:

```json
{
  "requiresInvite": true,
  "defaultMode": "auto",
  "maxMode": "high",
  "defaultIPHiding": "nonContacts",
  "encryption": { "newConversations": "e2ee" },
  "abuseContact": "",
  "retention": { "inboxDays": 30, "attachmentDays": 30, "escrowDays": 365 },
  "limits": { "attachmentBytes": 104857600, "attachmentBytesPerDay": 1073741824 }
}
```

Mode names match AppCore: `auto`, `ultraLow`, `dataSaver`, `balanced`, `high`. IP hiding values: `never`, `nonContacts`, `always`. Loading fails if `defaultMode` is above `maxMode`, or if `newConversations` is `server_readable` and `APP_ESCROW_KEY_FILE` is unset.

## 6. Data model (Postgres)

Migrations are embedded SQL files run in filename order. Prefixes are reserved per lane so that parallel branches never collide: `0xxx` F, `1xxx` A, `2xxx` B, `3xxx` C, `4xxx` D, `5xxx` E.

`0001_core.sql` (F):
- `accounts(id uuid pk, username text unique not null, email text not null, email_lower text unique not null, display_name text not null default '', find_by_email bool not null default false, created_at timestamptz not null default now())`
- `devices(id uuid pk, account_id uuid not null references accounts on delete cascade, name text not null, platform text not null check (platform in ('iphone','mac')), created_at timestamptz not null default now(), last_seen_at timestamptz not null default now())`

`1003_android_platform.sql` (A): `devices.platform` and `link_requests.platform` also accept `android`.

All later tables reference `accounts(id)` or `devices(id)` with `on delete cascade`. **Revoking a device deletes its row, and deleting an account deletes its row.** The cascade removes sessions, KeyPackages, inbox items, conversation membership, push tokens and blocks, with no code in other lanes.

## 7. HTTP API

JSON in and out. Authenticated endpoints need `Authorization: Bearer <session token>`. Errors look like `{"error":{"code":"<code>","message":"<english, for logs>","details"?:{…}}}`. Status 429 adds `Retry-After`.

| Code | Status | Code | Status |
|---|---|---|---|
| `bad_request` | 400 | `invalid_username` | 400 |
| `invalid_email` | 400 | `code_invalid` | 400 |
| `unauthorized` | 401 | `forbidden` | 403 |
| `not_found` | 404 | `username_taken` | 409 |
| `stale_epoch` | 409 | `invite_required` | 403 |
| `conflict` | 409 | | |
| `invite_invalid` | 403 | `expired` | 410 |
| `too_large` | 413 | `rate_limited` | 429 |
| `escrow_required` | 400 | `escrow_forbidden` | 400 |
| `calls_disabled` | 503 | `internal` | 500 |

`conflict` (409) is the admin API's answer when a change would break a rule, such as removing the last enabled admin.

### 7.1 Identity (lane A)

Usernames: `^[a-z][a-z0-9_]{2,31}$` (same rule as AppCore `UsernameCheck`). Reserved names: `admin`, `root`, `support`, `security`, `abuse`, `system`, `help`. Emails are trimmed and compared lowercase. They must contain one `@` with a dot in the domain, and be at most 254 characters long.

| Method and path | Auth | Request → response |
|---|---|---|
| `GET /v1/usernames/{name}` | – | → `{"available":bool}`; `invalid_username` |
| `POST /v1/invites/check` | – | `{code}` → `{"valid":bool}` (does not use up the invite) |
| `POST /v1/signup/start` | – | `{inviteCode?, username, email}` → `{signupID}`; sends the email code. Errors: `invite_required`, `invite_invalid`, `invalid_username`, `username_taken`, `invalid_email` |
| `POST /v1/signup/verify` | – | `{signupID, code}` → `{}`; `code_invalid`, `expired`, `rate_limited` (after 5 wrong codes) |
| `POST /v1/signup/passkey/options` | – | `{signupID}` (must be verified) → WebAuthn `PublicKeyCredentialCreationOptions` JSON |
| `POST /v1/signup/finish` | – | `{signupID, credential?, deviceName, platform}` → `{accountID, deviceID, sessionToken}`. Uses up the invite and creates the account, device, passkey (if `credential` is given) and session in one transaction. `platform` is `iphone`, `mac` or `android`. `username_taken` if the name was taken meanwhile. `credential` is optional because iOS allows passkeys only for hosts in the app's associated domains: on other self-hosted servers the account signs in by email code (client plan 6). Android passkeys are accepted only from origins `android:apk-key-hash:<base64url, no padding, of the fingerprint>` for the apps in `APP_ANDROID_APPS` (none by default) |
| `POST /v1/signin/passkey/options` | – | `{}` → `{challengeID, options}` (discoverable credential, user verification required) |
| `POST /v1/signin/passkey/finish` | – | `{challengeID, credential, deviceName, platform}` → `{accountID, deviceID, sessionToken}` (new device) |
| `POST /v1/signin/email/start` | – | `{email}` → `202 {}` always, whether or not the email exists |
| `POST /v1/signin/email/finish` | – | `{email, code, deviceName, platform}` → `{accountID, deviceID, sessionToken}`; `code_invalid` for an unknown email too |
| `POST /v1/me/passkeys/options` | ✓ | → creation options (adds a passkey after email recovery) |
| `POST /v1/me/passkeys` | ✓ | `{credential}` → `{}` |
| `GET /v1/me` | ✓ | → `{accountID, username, email, displayName, findByEmail}` |
| `DELETE /v1/me` | ✓ | → `204`; deletes the account and everything that cascades from it |
| `GET /v1/devices` | ✓ | → `{devices:[{id, name, platform, createdAt, lastSeenAt, current}]}` |
| `DELETE /v1/devices/{id}` | ✓ | → `204`; own devices only (`not_found` otherwise); sends `device.revoked` to the remaining devices |
| `POST /v1/link` | – | `{deviceName, platform}` → `{linkID, secret, claimToken, expiresAt}`. The new device also makes an X25519 key pair and shows the QR code `https://<host>/link/<linkID>#<secret>.<publicKey b64url>`; the approver seals `sealed` to that key (HPKE, same suite as escrow, `info = "link-v1"`), so the server cannot read the link data |
| `POST /v1/link/{linkID}/info` | ✓ | `{secret}` → `{deviceName, platform, createdAt}` for the approval screen (client plan 6, OPEN-24); `forbidden` on a wrong secret, `expired` after 5 minutes |
| `POST /v1/link/{linkID}/approve` | ✓ | `{secret, sealed?}` → `204`. `sealed` is at most 64 KiB of opaque data handed to the new device |
| `POST /v1/link/{linkID}/claim` | – | `{claimToken}` → `202 {}` while pending; once approved, `200 {accountID, deviceID, sessionToken, sealed}`, exactly once; `expired` after 5 minutes or after a claim |

Values: email codes are 6 digits, valid for 10 minutes, with at most 5 attempts, and are stored as SHA-256. Session tokens are 32 random bytes, base64url, and stored as SHA-256. They never expire and end on revocation or account deletion. The link secret and claim token are 16 random bytes each, stored hashed. Invites are created with the CLI `appserver invite create [--uses N] [--days D]`, and codes are 10 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no I, O, 0, 1) in the form `XXXXX-XXXXX`, which AppCore `InviteLink.parse` accepts.

### 7.2 Delivery (lane B)

Phase 1 conversations have exactly two accounts. The server tracks which **devices** are in each conversation, because that is who it delivers to.

| Method and path | Auth | Request → response |
|---|---|---|
| `POST /v1/keypackages` | ✓ | `{keyPackages:[b64]}` → `{count}`; at most 16 KiB each, at most 100 stored per device |
| `GET /v1/keypackages/count` | ✓ | → `{count}` |
| `POST /v1/keypackages/claim` | ✓ | `{accountID, deviceIDs?}` → `{keyPackages:[{deviceID, keyPackage}]}`, one per device of that account, or only per device listed in the optional `deviceIDs` (omitted or empty: all; IDs that are not devices of the account are ignored). A device's last KeyPackage is returned without being removed. If the target blocked the caller, or does not exist: `not_found` |
| `POST /v1/conversations` | ✓ | `{members:[accountID]}` (exactly one other account) → `{conversationID, mode, epoch:0}`. The caller's device is the only member device at epoch 0 |
| `GET /v1/conversations/{id}` | ✓ (member device) | → `{conversationID, mode, epoch}`; a device that joined by Welcome learns the mode here. Non-members: `not_found` |
| `POST /v1/conversations/{id}/commits` | ✓ (member device) | `{epoch, commit:b64, welcome?:b64, addDevices?:[id], removeDevices?:[id]}` → `{epoch}` (the new epoch). The commit is accepted only if `epoch` equals the current epoch; otherwise `409 stale_epoch` with `{"currentEpoch":n}` in `error.details`. Added devices must belong to the conversation's accounts |
| `POST /v1/conversations/{id}/messages` | ✓ (member device) | `{epoch, ciphertext:b64, escrow?:b64, clientMsgID}` → `{seq}`. Idempotent per (device, clientMsgID) for 7 days. `epoch` above the current epoch → `bad_request`. Mode `e2ee` with an escrow copy → `escrow_forbidden`; mode `server_readable` without one → `escrow_required` |
| `GET /v1/inbox?after=<cursor>&limit=<n>` | ✓ | → `{items:[InboxItem], more:bool}`; `limit` defaults to 100, maximum 500 |
| `POST /v1/inbox/ack` | ✓ | `{upTo:cursor}` → `204`; deletes this device's items with cursor ≤ `upTo` |

`InboxItem = {cursor:int64, kind:"message"|"commit"|"welcome", conversationID, seq?, epoch, senderAccountID, senderDeviceID, payload:b64, sentAt}`

Fan-out rules:
- A commit goes to every member device present *before* the commit, **including the sender** (so a sender that lost the `200` can tell its commit won; client plan 4).
- A welcome goes to each added device.
- A message goes to every member device except the sender.
- Each delivery is one inbox row plus `Notify(device, Event{Type:"inbox", Data:item, Push:PushMessage, PushPayload:payload})`.
- When a recipient's account has blocked the sender's account, the server skips that recipient silently and the sender still gets `200`. For a **welcome**, the blocked recipient's device is skipped and not added to the conversation. For a **commit** in an existing conversation, the row is still delivered (skipping it would break the recipient's encryption state) but with no push.

Inbox rows older than `retention.inboxDays` are deleted hourly.

Escrow keys: `appserver keygen escrow` prints a new X25519 private key as PKCS#8 PEM. Escrow (wire format: the stored blob is the HPKE encapsulated key (32 bytes) followed by the ciphertext, single-shot with empty AAD — CryptoKit `HPKE.Sender` `encapsulatedKey || seal(_:)`): HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, `info = "escrow-v1"`. The plaintext is client JSON `{conversationID, senderAccountID, sentAt, body}`. Escrow rows keep `retention.escrowDays`. The CLI command `appserver escrow export --conversation <id> --reason <text>` decrypts the copies, writes JSON lines to stdout, and appends a row to `escrow_access_log`.

### 7.3 Realtime, calls, TURN (lane C)

`GET /v1/ws` (auth header) upgrades to a WebSocket. Each device has one live connection: a new connection closes the old one with code 4001 `replaced`, and revocation closes it with code 4003 `revoked`. The server pings every 25 s and drops the connection after 2 missed pongs.

Frames: `{"type":string, "id"?:string, "data"?:object}`. When a client frame has an `id`, the server answers with `{"type":"ack","id"}` or `{"type":"error","id","data":{"code"}}`.

Server → client: `inbox`, `device.revoked {deviceID}`, `call.ring`, `call.answered`, `call.signal`, `call.ended`. Every server-sent `call.signal` carries `{callID, fromDeviceID, payload}`: before the answer several callee devices may signal the caller (client plan 8 has callees make the offer), so the caller must know which device each signal came from.

Client → server, for calls (`payload` is always an opaque MLS application message the server cannot read, carrying the SDP and DTLS fingerprint):

| Frame | Data | Effect |
|---|---|---|
| `call.invite` | `{callID, to:accountID, video, payload}` | Rings every device of `to` with `call.ring {callID, fromAccountID, fromDeviceID, video, payload}`, sending a `PushCall` push to offline devices. If `to` blocked the caller, nothing rings and the caller gets `call.ended missed` after the timeout |
| `call.answer` | `{callID, payload}` | Caller gets `call.answered {callID, deviceID, payload}`; the other callee devices get `call.ended {callID, reason:"answered_elsewhere"}` |
| `call.signal` | `{callID, payload}` | Before the answer, caller signals go to every ringing device and callee signals go to the caller. After the answer, signals go only between the two connected devices |
| `call.decline` | `{callID}` | Ends the call as `declined` on every device |
| `call.hangup` | `{callID}` | Ends the call as `hangup` on every device |
| `call.status` | `{callID}` | Acknowledged with `data:{state:"ringing"\|"answered"\|"ended", reason?}`. Used by a device woken by a push |

Call states are `ringing`, `answered` and `ended`. End reasons are `declined`, `hangup`, `missed`, `answered_elsewhere`, `failed`, and `missed` is used when ringing goes unanswered for 45 s. An answered call is forgotten after 4 h. An ended call is kept for 60 s so `call.status` can report it. Only the caller's device and the callee account's devices may act on a call. Anything else gets `not_found`.

`GET /v1/turn` (auth) → `{iceServers:[{urls:[…], username, credential}], ttl:43200}`, or `calls_disabled`. There is one `iceServers` entry per healthy relay (same username and credential), and each contributes `stun:H:3478`, `turn:H:3478?transport=udp`, `turn:H:3478?transport=tcp` and `turns:H:443?transport=tcp`. The username is `<unix expiry>:<8 random hex>`. The credential is `base64(HMAC-SHA1(secret, username))`.

Relay health: every 30 s, send a STUN Binding Request (RFC 5389) over UDP to `H:3478` with a 2 s timeout. A relay becomes unhealthy after 3 failures in a row and healthy again after 1 success. Healthy relays are listed fastest first. If no relay is healthy, all of them are listed.

### 7.4 Push (lane D)

Server endpoints: `PUT /v1/push/tokens/{kind}` (kind `alert`, `voip` or `fcm`) `{sealed:b64url}` → `204` (at most 2 KiB decoded; larger is `bad_request`), `PUT /v1/push/tokens/unifiedpush` `{endpoint, p256dh, auth}` (not sealed; endpoint at most 1024 characters, `https` and port 443 only; `bad_request` when the server has no VAPID key) → `204`, and `DELETE /v1/push/tokens/{kind}` → `204`. A device has one of `fcm` and `unifiedpush`: uploading one deletes the other.

**Sealed handle:** the device seals `{"t":"<apns token hex>","k":"alert"|"voip","e":"production"|"sandbox","b":"<bundle id>"}` with HPKE to the gateway's public key (the same suite as escrow, `info = "push-handle-v1"`). The server stores it as received and cannot read it.

**Sending (`push.Sender`, implements `core.Pusher`):**
- `PushCall` uses the device's `voip` handle if it has one (iPhone), then `alert` (Mac), then `fcm`, then `unifiedpush`.
- `PushMessage` uses `alert`, then `fcm`, then `unifiedpush`.
- A `unifiedpush` push goes straight to the distributor endpoint (RFC 8030/8291/8292, see [push](../../protocol/push.md#android-pushes-through-unifiedpush)), with no gateway, so it works when `APP_PUSH_GATEWAY_URL` is empty. The endpoint is untrusted: https and port 443 only, no redirects, no proxy, and the resolved address must be public (checked at connect time). `APP_VAPID_KEY_FILE` holds the P-256 VAPID key (`appserver keygen vapid`); `/v1/server` publishes it as `push.vapidKey`. `404` and `410` delete the registration. UnifiedPush sends run in their own pool and never hold a gateway push slot. Each send has an 8-second limit. At most 64 are in flight in all, and at most 8 message sends to one endpoint host; a send over either limit is dropped and the device fetches its inbox. A device has one message send in flight: later message pushes are coalesced, keeping only the latest payload, which goes out when the current send finishes, so the per-device limit never drops a message. A call push skips that queue and goes out at once; only the global limit can drop it. `push.Module.WaitBackground` waits for in-flight sends at shutdown.
- No handle means no push and no error.

**Padding:** the body is a 2-byte big-endian length, then the payload, then zeros up to the smallest bucket of 256, 512, 1024, 2048 or 3072 bytes. A payload over 3070 bytes is replaced by an empty one, and the device then fetches its inbox.

**Server → gateway:**
- The request is `POST {gateway}/v1/push` with body `{handle:b64, kind:"message"|"call", data:b64, nonce?:string}`, where `b64` is standard base64 with padding, not base64url. Decision 3 covers client-facing fields only.
- It carries three headers: `X-Server-ID`, `X-Timestamp` (Unix seconds) and `X-Signature`.
- `X-Signature` is standard base64 (with padding) Ed25519 over `"v1\n"+method+"\n"+path+"\n"+timestamp+"\n"+hex(sha256(body))`.
- If the gateway answers `410`, the server deletes that handle.

**Gateway (`cmd/pushgw`):**

Endpoints:
- `GET /v1/gateway-key` → `{publicKey:b64url}` (base64url, no padding, like every client-facing binary field)
- `POST /v1/servers` `{publicKey, name, contact}`, signed with that key → `{serverID}`. Limited to 5 per hour per IP.
- `POST /v1/push`

Checks before sending:
- The server is known and not disabled.
- The signature is valid.
- The timestamp is within ±300 s of the gateway's clock.
- The same signature has not been seen in the last 10 minutes (a replay).
- The handle opens with the gateway's key.
- The handle's bundle ID is in `PUSHGW_BUNDLE_IDS` (for an `fcm` handle: its application ID is in `PUSHGW_ANDROID_APP_IDS`, and the gateway has FCM configured).
- The handle's kind fits: `call` may use `voip`, `alert` or `fcm`; `message` must use `alert` or `fcm`.

Limits: 1000 per minute per server, 120 per hour per handle for messages, and 20 per hour per handle for calls.

Besides the codes of section 7, the gateway also answers `conflict` (409, a replayed signature), `gone` (410, the handle is no longer valid) and `upstream` (502, APNs failed). It returns no other codes.

**APNs:**
- HTTP/2 `POST https://api.push.apple.com/3/device/<token>`, or `api.sandbox.push.apple.com` for sandbox handles.
- The auth JWT is ES256 with header `{alg:ES256, kid}` and claims `{iss:teamID, iat}`, refreshed every 50 minutes.
- Alert push:
  - headers `apns-push-type: alert`, `apns-priority: 10`, `apns-topic: <bundle>`
  - `d` is standard base64 with padding. It is an APNs payload field, not a client API field, so decision 3 does not apply
  - body `{"aps":{"alert":{"title":"[App]","body":"New message"},"mutable-content":1,"sound":"default"},"d":"<b64 data>"}`
- VoIP push:
  - headers `apns-push-type: voip`, `apns-priority: 10`, `apns-expiration: 0`, `apns-topic: <bundle>.voip`
  - body `{"d":"<b64 data>"}`
- APNs `410`, or `400 BadDeviceToken`, becomes a `410` to the server.

**FCM (`fcm` handles):**
- HTTP/2 or HTTP/1.1 `POST https://fcm.googleapis.com/v1/projects/<PUSHGW_FCM_PROJECT_ID>/messages:send` with `Authorization: Bearer <access token>`. The access token comes from the service-account key: an RS256 JWT (`iss` = the account's email, `scope` = `https://www.googleapis.com/auth/firebase.messaging`, `aud` = the key's `token_uri`, one hour lifetime) exchanged at that URI. It is cached until 5 minutes before it expires, and a new one is never minted more than once a minute. No redirects are followed.
- Body `{"message":{"token":"<t>","data":{"k":"message"|"call","d":"<b64 data>"},"android":{"priority":"HIGH","ttl":"86400s"|"45s"}}}`. `d` is standard base64 with padding of the padded payload, unchanged, as for APNs. The TTL is `45s` for a call and `86400s` for a message.
- **Size rule:** FCM data is limited to 4096 bytes. A padded payload over 2048 bytes (the 3072 bucket, 4096 characters in base64) is replaced by the 256-byte empty one (length 0, "fetch the inbox"). The gateway decides this from the padded length alone.
- FCM `UNREGISTERED` becomes a `410` to the server. Any other `404`, including `NOT_FOUND` (which a wrong URL, proxy or project also returns), is a gateway-side error: `502`, and the handle is kept.
- An `fcm` handle must have `e = "production"`; anything else is `bad_request`.

`appserver keygen push` prints a new Ed25519 private key as PKCS#8 PEM; `appserver push register --gateway URL --name N --contact C` registers it and prints the server ID. Gateway configuration: `PUSHGW_LISTEN`, `PUSHGW_DATABASE_URL`, `PUSHGW_HPKE_KEY_FILE`, `PUSHGW_APNS_KEY_FILE` (.p8, held by the owner only), `PUSHGW_APNS_KEY_ID`, `PUSHGW_APNS_TEAM_ID`, `PUSHGW_BUNDLE_IDS`, `PUSHGW_APNS_URL` (overrides both APNs hosts; for tests). Android handles need `PUSHGW_FCM_PROJECT_ID`, `PUSHGW_FCM_KEY_FILE` (the service-account JSON, held by the owner only) and `PUSHGW_ANDROID_APP_IDS` (all three or none), plus `PUSHGW_FCM_URL` (overrides the FCM host; for tests).

### 7.5 Social, attachments, server info (lane E)

| Method and path | Auth | Request → response |
|---|---|---|
| `GET /v1/users/{username}` | ✓ | exact match only → `{accountID, username, displayName}`; `not_found`. 60 per hour per account |
| `POST /v1/discover/email` | ✓ | `{email}` → the same shape, only if that account has `findByEmail` on; otherwise `not_found`. 20 per day per account |
| `PATCH /v1/me/settings` | ✓ | `{displayName?, findByEmail?}` → `204`. The display name is at most 64 characters with no control characters |
| `PUT /v1/blocks/{accountID}` / `DELETE …` | ✓ | → `204` |
| `GET /v1/blocks` | ✓ | → `{accountIDs:[…]}` |
| `POST /v1/reports` | ✓ | `{accountID, conversationID?, reason:"spam"\|"abuse"\|"other", messages:[{sentAt, text}] (≤50, shared with the user's consent), note?}` → `202`. Emailed to `abuseContact` if one is set. CLI: `appserver reports list` |
| `POST /v1/attachments` | ✓ | raw body (already encrypted by the client), `Content-Length` required → `{id, expiresAt}`; `too_large` |
| `GET /v1/attachments/{id}` | ✓ | → bytes, with HTTP Range support. The ID is 128 random bits and works as the access key |
| `GET /v1/server` | – | the server info document below |
| `GET /invite/{code}` | – | small HTML page: "Open this invite in [App]" (target of the universal link) |
| `GET /.well-known/apple-app-site-association` | – | `applinks` for `/invite/*` and `/link/*`, plus `webcredentials`, for `APP_APPLE_APP_IDS` |
| `GET /.well-known/assetlinks.json` | – | Digital Asset Links for `APP_ANDROID_APPS`: one entry per package with relations `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls` and the fingerprints as upper-case colon hex; `[]` when unset |

Server info document:
```json
{ "protocol": 1, "host": "chat.example.org", "requiresInvite": true,
  "policy": { "defaultMode": "auto", "maxMode": "high", "defaultIPHiding": "nonContacts" },
  "encryption": { "newConversations": "e2ee", "escrowKey": null },
  "push": { "gateway": "https://push.example.org", "vapidKey": "<base64url P-256 public key, or null>" },
  "features": ["calls", "video", "linking"], "federation": false,
  "limits": { "attachmentBytes": 104857600 } }
```

## 8. Security rules (all lanes)

- **Logging:** never log session tokens, codes, link secrets, email addresses, push handles, payloads or IP addresses. Log account and device IDs only at debug level.
- **Enumeration:** for unknown and known values, these return identical status codes and bodies: email sign-in start, email sign-in finish errors, KeyPackage claim for a blocked or unknown account, user lookup, and email discovery.
- **Limits, in memory per key:**
  - username check: 30/min per IP
  - invite check: 10/hour per IP
  - sign-up start: 5/hour per IP
  - email code send: 3/hour per email and 10/hour per IP
  - passkey options: 30/min per IP
  - link start: 10/hour per IP
  - link claim: 60/min per link
  - KeyPackage claim: 100/hour per account
  - messages: 600/min per device
  - reports: 20/day per account
  - attachment bytes: `attachmentBytesPerDay` per account
- **Request bodies** are capped at 1 MiB, except attachments.
- **Comparisons:** compare secrets in constant time and store only hashes.

## 9. Metadata the operator can see

The server can see:
- accounts, usernames, emails, display names
- device names, platforms, last-seen times
- which accounts share a conversation, and which devices are in it
- message timing and sizes
- call timing between accounts
- block lists and reports
- attachment sizes and timing

The gateway can see which server sends to which opaque handle, and when. Apple can see the push token, and the timing and size of pushes. This list goes into the admin guide (lane E).
