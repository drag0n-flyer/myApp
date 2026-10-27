# Protocol Specification

**Status:** draft for owner review. The owner reviews every file in this folder, because it describes protocol state machines ([plan 9](../superpowers/plans/2026-10-27-security-docs-release.md) Task 3).
**Applies to:** protocol version 1 (`protocol: 1` in `GET /v1/server`, see [versioning](versioning.md)), as designed in the plans dated 2026-10-27 ([roadmap](../superpowers/plans/README.md)).

This is the written contract that the Go server and the Swift clients both follow. It says what travels between them and what each side must check before it trusts what arrived. It does not repeat the HTTP API.

Endpoints and frames: see [server design §7](../superpowers/specs/2026-10-27-server-design.md#7-http-api). That section stays the single source of truth. If this folder and the server design disagree about an endpoint, a frame, a field or an error code, the server design wins and this folder has a bug.

The words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119. Where a document leaves a gap that no plan closes, it says so and cites the matching `OPEN-n` risk in the [threat model](../security/threat-model.md#8-open-risks).

## Documents

| Document | What it fixes |
|---|---|
| [MLS profile](mls-profile.md) | Cipher suite, credentials, KeyPackages, groups, the commit flow, epochs, device removal, exporter labels |
| [Device linking](device-linking.md) | How a new device joins an account, and what the sealed link snapshot holds |
| [Calls](calls.md) | What the call signaling payloads hold, the fingerprint check, relay policy, TURN credentials, ICE restarts |
| [Push](push.md) | The sealed push handle, padding, the server-to-gateway signature, what the notification extension does |
| [Escrow](escrow.md) | The escrow copy in server-readable conversations |
| [Versioning](versioning.md) | The `protocol` number, what a client does with an unknown one, federation-ready identifiers |

## Message content format

Message content format: see [plan 7 (client messaging)](../superpowers/plans/2026-10-27-client-messaging.md#content-envelope-exact-json), `MessageBody` schema. Plan 7 names that schema the content envelope (`ContentEnvelope`). This folder calls the same JSON object a `MessageBody`: the plaintext of every MLS application message.

TODO(plan 7): replace the link above with the schema document once plan 7 publishes it, and use one name for the object.

## Conventions

- **Binary data in JSON** is base64url without padding (RFC 4648 §5): KeyPackages, commits, Welcomes, ciphertexts, escrow copies, sealed blobs, push handles, public keys, call payloads and the push field `d`. Writers MUST NOT add padding. There are three exceptions, each stated where it applies:
  - the TURN credential is standard base64 with padding, because coturn's shared-secret scheme defines it that way ([calls](calls.md#turn-credentials))
  - the binary fields inside a `MessageBody` use standard base64, because that object is client-internal (plan 7)
  - DTLS fingerprints are colon-separated hex, as in SDP
- **Text is UTF-8** everywhere: JSON bodies (no byte-order mark), MLS credential identities, HPKE `info` strings and signing strings. Strings are compared and signed as their exact UTF-8 bytes, with no terminator and no normalization.
- **Identifiers** (accounts, devices, conversations) are lowercase UUID strings made by the server. Call IDs are UUID strings and message IDs are lowercase UUIDs, both made by clients. Every identifier is an opaque string to the receiver ([versioning](versioning.md#federation-ready-identifiers)).
- **Times:** a `Time` field made by the server (`sentAt`, `createdAt`) is RFC 3339 in UTC. A `MessageBody` time (`at`) is an integer count of milliseconds since 1970. The push header `X-Timestamp` and a TURN expiry are decimal Unix seconds.
- **Keys:** X25519 and Ed25519 public keys travel as their raw 32 bytes.
- **One HPKE suite** serves three uses. Each use has its own `info` string, so a ciphertext made for one use never opens for another:

| Use | `info` | Sealed to | Document |
|---|---|---|---|
| Escrow copy | `escrow-v1` | the server's escrow key | [escrow](escrow.md) |
| Push handle | `push-handle-v1` | the push gateway's key | [push](push.md) |
| Device link snapshot | `link-v1` | the new device's key from its QR code | [device linking](device-linking.md) |

The suite is HPKE (RFC 9180) in base mode (`0x00`), with DHKEM(X25519, HKDF-SHA256) (`0x0020`), HKDF-SHA256 (`0x0001`) and ChaCha20-Poly1305 (`0x0003`). The AAD is empty. The output is `enc ‖ ct`: the 32-byte encapsulated key first, then the ciphertext with its 16-byte tag, so its length is `32 + len(plaintext) + 16`. Go uses `crypto/hpke`. Swift uses CryptoKit's `HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly`.

## Owner to confirm

These rules and values are in this folder but are not fixed by the server design, the roadmap or a plan, or only a plan fixes them. The owner confirms each one before the spec is final.

| Rule or value | Where |
|---|---|
| A device that missed `device.revoked` catches up by comparing group leaves with `GET /v1/devices`; Remove commits within 24 hours of coming online | [MLS profile](mls-profile.md#removing-a-device) |
| KeyPackage refill: check at most every 6 hours, upload 50 when fewer than 10 remain; 90-day lifetime; keep the last-resort secrets after a Welcome | [MLS profile](mls-profile.md#keypackages) |
| Three retained epochs (a message more than two epochs late is lost) | [MLS profile](mls-profile.md#application-messages) |
| Group IDs are 16 bytes and receivers accept any length | [MLS profile](mls-profile.md#groups-and-conversations) |
| At least one retry after `stale_epoch`; an unknown-outcome commit is never resent, the inbox is fetched first | [MLS profile](mls-profile.md#commits) |
| Receivers act on the MLS credential, not on server-stated sender fields | [MLS profile](mls-profile.md#identity) |
| The exporter context is empty; `call-fingerprint-v1` is reserved and unused | [MLS profile](mls-profile.md#exporter-labels) |
| A fresh link key pair per attempt; a `sealed` that fails to open is discarded whole | [Device linking](device-linking.md) |
| `p.callID` must equal the frame's `callID`; every `a=fingerprint` line must match; renegotiation keeps the call's fingerprint | [Calls](calls.md#what-the-receiver-must-check) |
| The credential's device must equal the offering device or `fromDeviceID` | [Calls](calls.md#what-the-receiver-must-check) |
| `renegotiate` without `sdp` asks the caller to restart ICE; restart after 2.5 s, retry every 5 s, give up at 30 s | [Calls](calls.md#ice-restarts-and-reconnection) |
| Reuse cached TURN credentials only with at least 4 hours left | [Calls](calls.md#turn-credentials) |
| Strict unpadding; the gateway forwards padded bytes; `X-Signature` and `d` are base64url without padding | [Push](push.md) |
| Notification extension: 2 second lock wait, 25 second deadline, at most 20 inbox items | [Push](push.md#what-the-notification-extension-does-with-d) |
| Escrow `sentAt` is RFC 3339 | [Escrow](escrow.md#the-plaintext) |
| An unknown higher `protocol` stops all traffic and keeps local data, also for a signed-in account | [Versioning](versioning.md#what-a-client-does-with-an-unknown-version) |

Test vectors: none of its own. The shared vectors live in `test-vectors/`. They are generated only by the Go generator (plan 9 Task 4), never edited by hand, and verified by both Go and Swift. They are linked from [push](push.md), [escrow](escrow.md) and [calls](calls.md).
