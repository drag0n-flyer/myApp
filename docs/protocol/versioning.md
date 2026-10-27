# Versioning

**Status:** draft for owner review.
**Builds on:** [server design §7.5](../superpowers/specs/2026-10-27-server-design.md#75-social-attachments-server-info-lane-e) (the server info document); [GOAL.md](../../GOAL.md#7-federation-identity-and-server-policy-decisions) §7 (federation-ready); [plan 6](../superpowers/plans/2026-10-27-client-networking.md) (`ServerInfo`).

## The `protocol` number

`GET /v1/server` needs no sign-in and returns the server info document. Its `protocol` field is an integer that names the wire protocol this folder describes. The only version is `1`: JSON frames, base64url binary fields, the MLS profile, the `MessageBody` schema, and the sealed formats.

- A **breaking change** to any of those raises `protocol`. A breaking change is one that an older client would misread or reject.
- An **additive change** does not. Adding an optional JSON field, a new `features` entry or a new error code leaves `protocol` alone. Every reader ignores JSON fields it does not know.
- `features` lists optional capabilities (`calls`, `video`, `linking`). A client uses a capability only when it is listed.

## What a client does with an unknown version

A client has a highest `protocol` value that it supports. When the document says `protocol` above that value:

1. The client stops. It does not sign up, sign in, register a push token or send anything else to that server.
2. It shows "Update [App] to use this server".
3. It keeps any data it already has for that server.

The client runs this check every time it fetches the document: when the user adds a server, and when the app starts. An account that is already signed in follows the same rules when its server moves to a version the client does not know.

## Other version markers

Each format carries its own version, so one change does not drag the others along:

| Where | Marker | An unknown value |
|---|---|---|
| `GET /v1/server` | `protocol: 1` | "Update [App] to use this server" |
| `MessageBody` | `"v":1`, and a kind `k` | The message is stored and shown as "Update [App] to see this message" |
| Link snapshot | `"v":1` | The new device imports nothing ([device linking](device-linking.md#what-goes-in-sealed)) |
| HPKE uses | `info` ends in `-v1` | A ciphertext made for another `info` does not open |
| Push request signature | The signing string starts with `v1` | The gateway rejects the signature |
| MLS exporter label | `call-fingerprint-v1` | Not used until added to the [MLS profile](mls-profile.md#exporter-labels) |
| Test vector files | `"version":1` | The test fails |

## Federation-ready identifiers

Federation is not in version 1: every server is isolated, and `federation` is `false` in the info document ([GOAL §7](../../GOAL.md#7-federation-identity-and-server-policy-decisions)). The identifiers are shaped so that adding it later does not break them.

- **Today, an ID is a server-local UUID.** Account, device and conversation IDs are lowercase UUIDs that one server made. An account ID means something only on the server that issued it.
- **A federated ID will be `<uuid>@<host>`**, where `<host>` is the public host name of the server that issued the UUID (`APP_HOST`).
- **Clients treat every ID as an opaque string.** They store it exactly as received and compare it byte for byte. They do not parse it, assume its length, change its case or split it at `@`.
- **An account ID never contains `/`.** The MLS credential identity `<accountID>/<deviceID>` therefore still splits at the first `/` when account IDs become `<uuid>@<host>` ([MLS profile](mls-profile.md#identity)).
- **No federated ID is sent or accepted until a later `protocol` version defines federation.**

## Open points

- **No plan handles an unknown `protocol` yet.** Plan 6's `ServerInfo` model and `AccountError` have no case for it, and the string "Update [App] to use this server" is not in any String Catalog task. A plan 6 task has to add the check and the string. The rules for an account that is already signed in are a proposal made here.
- **A version below the client's minimum** is not defined. Only version 1 exists.

Test vectors: none.
