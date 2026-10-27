# MLS Profile

**Status:** draft for owner review (cryptography and protocol state machines).
**Builds on:** MLS, RFC 9420; [GOAL.md](../../GOAL.md#encryption-protocol) §5; [plan 4](../superpowers/plans/2026-10-27-mls-client-core.md) (the `MLSEngine` contract); [plan 7](../superpowers/plans/2026-10-27-client-messaging.md) (how the clients use it); [server design §7.2](../superpowers/specs/2026-10-27-server-design.md#72-delivery-lane-b) (the Delivery Service).

Every conversation is an MLS group. A one-to-one chat is a group of two accounts, and every device of both accounts is its own member. The server is a Delivery Service: it stores KeyPackages, puts commits in strict epoch order, and fans out opaque bytes. It never joins a group and holds no MLS key. The endpoints named below are defined in the server design, not here ([README](README.md)).

## Profile

| Item | Value |
|---|---|
| Cipher suite | `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`, value `0x0001` (RFC 9420 §17.1), for every group. Version 1 accepts no other suite. |
| Credential type | `basic` |
| Credential identity | The UTF-8 bytes of `<accountID>/<deviceID>` |
| Signature key | Ed25519. One key per device, made on the device and never synced. A lost device means a new identity. |
| Required capabilities | None beyond the RFC 9420 defaults |
| Extensions | None of our own. A Welcome carries the RFC 9420 `ratchet_tree` extension, so a joining device needs no separate tree fetch. |
| Proposals | Never sent on their own. Every change is a commit with its Add or Remove included. A standalone proposal is rejected as malformed. |
| External commits, PSKs, ReInit | Not used in version 1 |
| Welcome | One Welcome message for all members added by a commit |
| Message padding | The sender pads application messages with the library's default step function. It is not negotiated. |

## Identity

An identity is `<accountID>/<deviceID>`, both lowercase UUIDs, as the server issued them. An account ID never contains `/`, so a receiver splits at the first `/`.

- The sender of an application message is the identity in the MLS credential of its leaf. A client acts on that, never on the `senderAccountID` or `senderDeviceID` in an inbox item or a frame, because those are the server's statements. This is why a forged message cannot be pinned on a contact ([threat model](../security/threat-model.md#53-delivery-and-mls-delivery-service), DLV-S-01).
- A client compares the account part with the contact it expects. Calls do this on every signaling message ([calls](calls.md#what-the-receiver-must-check)).

## KeyPackages

- **Lifetime:** 90 days. A KeyPackage's `not_after` is its creation time plus 90 days.
- **Upload:** `POST /v1/keypackages`. The server accepts at most 16 KiB per KeyPackage and keeps at most 100 per device.
- **Refill:** a device reads `GET /v1/keypackages/count` after sign-in and then at most every 6 hours. When the count is below 10 it uploads 50 more. That keeps at most 59 stored.
- **One last-resort KeyPackage per device.** The server never removes a device's final KeyPackage when it is claimed (server design §7.2), so a device that has run out can still be added to a conversation. That package can be used in more than one Add. The device therefore keeps the private keys of its last-resort KeyPackage after a Welcome has used it, and drops them only when that package has expired or a newer one has replaced it. Reuse weakens forward secrecy a little in exchange for reliability (RFC 9420 §16.8, [OPEN-17](../security/threat-model.md#open-17-forward-secrecy-trade-offs)).
- **Claim:** `POST /v1/keypackages/claim` returns one KeyPackage per device of the account. The MLS library checks the signature, the cipher suite and the lifetime when it processes the Add. No version 1 task compares the credential identity with the `<accountID>/<deviceID>` that the server named, so a malicious server can offer a device it controls ([OPEN-6](../security/threat-model.md#open-6-no-contact-or-device-verification), DLV-S-02).

## Groups and conversations

- **One MLS group per conversation.** A conversation has exactly two accounts. Its group holds one leaf for every device of both accounts.
- **The group ID is 16 random bytes** from a cryptographically secure generator, chosen by the creating device. A receiver treats a group ID as an opaque string and MUST accept any length.
- **The server's conversation ID and the group ID are independent.** The server never sees a group ID. Each client stores the pair. A device that joins by Welcome learns the conversation ID from the inbox item that carried the Welcome, and the group ID from the Welcome itself.
- **The encryption mode** (`e2ee` or `server_readable`) belongs to the conversation and is set once, at creation, by the server. A creator reads it from the create response. A device that joined by Welcome reads it from `GET /v1/conversations/{id}`. The mode only ever comes from the server ([escrow](escrow.md#when-an-escrow-copy-is-sent), [OPEN-18](../security/threat-model.md#open-18-clients-trust-the-server-for-the-conversation-mode)).
- **Two conversations for one pair** can exist when both people start a chat at the same moment. Both stay receivable. A sender uses the active conversation with the smallest conversation ID, so both sides pick the same one without talking (plan 7 Design 1).

Creating a conversation, in order:

1. The creator claims KeyPackages for the other account and for its own account, leaving out its own device.
2. `POST /v1/conversations` returns the conversation ID, the mode and `epoch: 0`.
3. The creator makes the group (epoch 0, itself the only leaf) and builds one commit that adds every claimed KeyPackage.
4. `POST /v1/conversations/{id}/commits` with `epoch: 0`, the commit, the Welcome and `addDevices` naming each added device.
5. On `200` the creator merges the commit. The group is at epoch 1. The server delivers the Welcome to each added device as a `welcome` inbox item.

## Commits

Epochs advance only by commits, and the server lets exactly one commit win per epoch.

1. The device asks its MLS engine for a commit (`add` or `remove`). The result is a `CommitBundle` with the commit, an optional Welcome and `newEpoch`. The commit is **pending**: the group stays at the old epoch.
2. The device sends `POST /v1/conversations/{id}/commits` with `epoch` equal to the epoch the commit was made at (`newEpoch − 1`).
3. **HTTP 200:** the device calls `commitAccepted` and merges the commit. The server's reply carries the new epoch.
4. **`409 stale_epoch`:** another commit won. `error.details.currentEpoch` says where the group is. The device calls `commitRejected`, fetches its inbox and processes every item in order, then decides again whether its change is still needed (a device may already be added or removed) and retries with a new commit. This profile requires at least one retry. Plan 7 allows at most three attempts and then reports an epoch conflict. A `stale_epoch` is a definite answer only for the first time a commit is sent, which is why step 5 forbids sending it again.
5. **Unknown outcome** (a timeout, a lost connection, a crash after the request left): the commit stays pending. The device MUST NOT discard it and MUST NOT send the same commit again. It fetches its inbox. The server delivers every accepted commit to the sender too. If its own commit arrives, processing it merges the pending commit. If another device's commit arrives first, the engine clears the pending commit, because it lost. Only after that does the device build a new commit if it still needs one.

Rules the engine enforces (plan 4):

- A second commit cannot start while one is pending.
- Processing another device's commit clears a pending commit of ours, and a later `commitRejected` then does nothing.
- The engine reads group state from disk on every call, under one cross-process lock, because the Notification Service Extension changes the same state from another process.
- The same ciphertext twice is a typed error, never a corrupted group.

A commit goes to every member device present before the commit, the sender included. A Welcome goes to each added device. The server cannot read a commit, so it cannot check that `addDevices` and `removeDevices` match what the commit contains. It checks only that added devices belong to the conversation's two accounts.

## Application messages

- `encrypt` returns the ciphertext and the epoch it was made at. `POST /v1/conversations/{id}/messages` carries that `epoch`.
- **Future-epoch rule:** the server rejects a message whose `epoch` is above the conversation's current epoch (`bad_request`). It does not reject a lower one.
- A receiver keeps the keys of the three newest epochs (the current one and two before it). A message more than two epochs late cannot be decrypted and shows as one "could not be decrypted" placeholder. This trades forward secrecy for tolerance of late messages ([OPEN-17](../security/threat-model.md#open-17-forward-secrecy-trade-offs)).
- Inbox rows reach a device in cursor order, and a commit is stored before any message made at its new epoch. The live call frames have no such order ([calls](calls.md#what-the-receiver-must-check)).
- A message can arrive twice, once by push and once by inbox sync. The client skips an item whose payload hash it has already processed, and still acknowledges it.
- The plaintext is a `MessageBody` ([README](README.md#message-content-format)).

## Removing a device

Revoking a device deletes its row on the server. The cascade removes its conversation membership, KeyPackages and push tokens, and its session token stops working. It receives no further inbox items. It stays a leaf in every MLS group until another device commits a Remove.

- The server sends `device.revoked {deviceID}` to the other devices of the same account. The event is live only (plan 2 sends it without a push and keeps no copy), so a device that is offline never sees it.
- **Rule:** any remaining device of that account that is a member of a shared group commits a Remove for the revoked device's leaf (identity `<accountID>/<deviceID>`) in every such group, within 24 hours of coming online. The peer account's devices see the result as an ordinary commit.
- A device that missed the event finds the same facts by comparing, for each group, the leaves of its own account with `GET /v1/devices`, and removing every leaf that the list no longer shows. A device runs that comparison when it comes online.
- Two devices can try at once. The loser gets `stale_epoch`, processes the winner's commit, sees the leaf already gone and stops.
- A device that learns its own device ID was revoked wipes its local data (plan 7 Task A3).

Until the Remove lands, the revoked device still holds the keys of the epochs it saw ([threat model](../security/threat-model.md#53-delivery-and-mls-delivery-service), DLV-E-01).

## A linked device joins

A device added through [device linking](device-linking.md) starts with no group. It joins each existing conversation through a Welcome from a device that already belongs (plan 7 Task A3):

1. The new device uploads its KeyPackages after its first sign-in.
2. An existing device of the account compares `GET /v1/devices` with the leaves of each of its groups and finds the new device.
3. It claims the account's KeyPackages and commits an Add with a Welcome and `addDevices` for the new device, in every active conversation.
4. The new device processes the Welcomes from its inbox and reads each conversation's mode.

The new device cannot read messages sent before its leaf was added. Nothing tells the other devices that a device was added, and no frame announces it: they find out when they next compare the device list ([OPEN-7](../security/threat-model.md#open-7-new-devices-are-added-silently)).

## Exporter labels

A group's exporter secret is `MLS-Exporter(label, context, length)` (RFC 9420 §8.5). The engine offers `exportSecret(group:label:length:)`, which has no context parameter, so version 1 uses an empty context.

| Label | Status |
|---|---|
| `call-fingerprint-v1` | Reserved for call-key checks. Version 1 call setup does not use it: the DTLS fingerprint travels inside an MLS message ([calls](calls.md)). |

A label is the UTF-8 bytes of the string and ends in its version. No other label may be used until it is added here.

## Post-quantum

The standard suites are not post-quantum, so traffic recorded today is exposed to a future quantum attacker. GOAL §5 keeps the ability to change suites: a later version moves a group to a post-quantum suite with a ReInit (RFC 9420 §12.1.5). Version 1 never sends one. The `protocol` number ([versioning](versioning.md)) is how a server announces that change.

## Open points

These are gaps between this profile and the plans. They need an owner decision or a plan change.

- **Plan 4 does not cover the KeyPackage rules.** `key_packages(count)` has no lifetime parameter, no last-resort marker, and the engine deletes a KeyPackage's secrets after its Welcome (test `welcome_after_restart`). The server design also does not say which stored KeyPackage counts as "the last".
- **Group ID length:** plan 4 creates 32-byte group IDs, and this profile says 16. Receivers accept either.
- **Credential check:** no task checks a claimed KeyPackage's credential against the claim (OPEN-6).
- **Silent device adds:** OPEN-7.
- **Revocation catch-up:** plan 7's `handleRevoked` reacts to the live event only. The comparison with `GET /v1/devices` above is not in any task.
- **`removeDevices` for a revoked device:** the server design does not say whether a commit may name a device whose row revocation already deleted.
- **Withheld messages:** clients do not check for gaps in `seq` or the inbox cursor ([OPEN-19](../security/threat-model.md#open-19-the-server-can-withhold-messages-undetectably)).

Test vectors: none. The RFC 9420 crypto vectors run upstream, in plan 4's provider suite.
