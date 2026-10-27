# Escrow

**Status:** draft for owner review (cryptography; owner decision of 2026-10-27 to keep server-readable mode in Phase 1).
**Builds on:** [server design §7.2](../superpowers/specs/2026-10-27-server-design.md#72-delivery-lane-b) (escrow keys, checks and export); [plan 7](../superpowers/plans/2026-10-27-client-messaging.md#task-b1-outbox-and-escrow) (the sealing); [GOAL.md](../../GOAL.md#mode-b--server-readable--server-managed-encryption) §5 (Mode B); [threat model §6](../security/threat-model.md#6-mode-b-server-readable-specifics).

In a server-readable conversation the sender sends its normal MLS message and an **escrow copy**: the plaintext sealed with HPKE to the server's escrow public key. The server never joins the MLS group and holds no MLS key. In a strict end-to-end conversation there is no escrow copy, and the server rejects one.

Server-readable mode is off by default. A server turns it on with the policy `encryption.newConversations = "server_readable"`, which applies to new conversations only.

## The escrow key

The escrow key is an X25519 key pair made with `appserver keygen escrow`. The public half is in the server's info document as `encryption.escrowKey` (base64url of the 32-byte public key), or `null` when the policy never allows server-readable conversations. The private half stays on the server host, in the file named by `APP_ESCROW_KEY_FILE`. There is one key and no rotation ([OPEN-12](../security/threat-model.md#open-12-operator-accountability-is-advisory)).

## Sealing

- **Suite:** the shared HPKE suite in the [README](README.md#conventions), with `info = "escrow-v1"`.
- **Layout:** `enc (32 bytes) ‖ ct`, so an escrow copy is `32 + len(plaintext) + 16` bytes. The sender sends it as base64url in the `escrow` field of `POST /v1/conversations/{id}/messages`.
- **Owner of the code:** `Escrow.seal(_:serverKey:)` in the Swift `Messaging` package, and `delivery.SealEscrow` and `delivery.OpenEscrow` in the Go server. They must agree byte for byte ([test vectors](test-vectors/escrow.json)).

## The plaintext

The plaintext is UTF-8 JSON with these four keys:

| Key | Value |
|---|---|
| `conversationID` | The server's conversation ID |
| `senderAccountID` | The sender's own account ID. It is the sender's claim and nothing checks it. |
| `sentAt` | The time the sender made the message, RFC 3339 in UTC |
| `body` | The `MessageBody` of the message, as a JSON object |

```json
{"conversationID":"…","senderAccountID":"…","sentAt":"2026-10-27T09:30:00Z","body":{"v":1,"id":"…","at":1793093400000,"k":"text","t":"Lunch?"}}
```

`body` is the same `MessageBody` that the sender encrypts in the MLS message ([README](README.md#message-content-format)). It includes the attachment keys of a photo, voice message or file, so in a server-readable conversation the operator can read attachments while the encrypted blob exists. Key order and white space carry no meaning.

## When an escrow copy is sent

| Conversation mode | The server requires | If the client gets it wrong |
|---|---|---|
| `server_readable` | an `escrow` copy | `400 escrow_required` |
| `e2ee` | no `escrow` copy | `400 escrow_forbidden` |

- The mode is set once, when the conversation is created, from the server's policy. No Phase 1 call changes it, so a conversation cannot be downgraded.
- **A client adds an escrow copy only when its stored mode for that conversation is `server_readable`.** The mode comes from the server's create response or from `GET /v1/conversations/{id}`. It never comes from message content, even when a peer's content claims something else.
- On `escrow_required` or `escrow_forbidden`, the client refreshes the mode with `GET /v1/conversations/{id}` and retries once.
- The server checks only that a copy is present or absent. It cannot open the MLS message or the escrow copy's contents, so it cannot compare the two.

## Retention and export

An escrow row is kept for `retention.escrowDays`, 365 days by default.

`appserver escrow export --conversation <id> --reason <text>` decrypts the escrow copies of one conversation and writes them to standard output as JSON lines. It appends one row to `escrow_access_log` and fails without a reason. The log lives in the database the operator controls, so it is a record for honest operators and not tamper-evident ([OPEN-12](../security/threat-model.md#open-12-operator-accountability-is-advisory)).

## Threats

The escrow rows are in [section 5.4](../security/threat-model.md#54-escrow-server-readable-mode) of the threat model, and [section 6](../security/threat-model.md#6-mode-b-server-readable-specifics) explains why a strict conversation cannot be downgraded.

- **The escrow copy is not bound to the MLS message.** HPKE base mode authenticates nobody. Anyone with the public key can make a valid copy, and a member can send a copy that differs from the real message. The operator's export is the operator's record and not proof of what was said ([OPEN-20](../security/threat-model.md#open-20-escrow-copies-are-not-bound-to-the-mls-message)).
- **Clients trust the server for the mode.** A malicious operator can make a client treat an end-to-end conversation as server-readable and collect escrow copies of later messages. The client does not pin the mode it first saw ([OPEN-18](../security/threat-model.md#open-18-clients-trust-the-server-for-the-conversation-mode)).

## Open points

- **`sentAt`:** plan 7 names the key and does not fix its format. This document says RFC 3339, like every other server `sentAt`.
- **Escrow for call signals:** none. The outbox adds escrow only to stored messages, and call signals travel as MLS messages on the call frames ([calls](calls.md)).

Test vectors: [escrow.json](test-vectors/escrow.json).
