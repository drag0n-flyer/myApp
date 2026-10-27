# Calls

**Status:** draft for owner review (call signaling and media keys).
**Builds on:** [server design §7.3](../superpowers/specs/2026-10-27-server-design.md#73-realtime-calls-turn-lane-c) (frames, call states, TURN); [plan 8](../superpowers/plans/2026-10-27-client-calling.md#call-payload-sealed-by-plan-7) (the payload); [plan 7](../superpowers/plans/2026-10-27-client-messaging.md#content-envelope-exact-json) (the envelope); [GOAL.md](../../GOAL.md#suggested-infrastructure) §5 and §6.

Phase 1 has one-to-one calls only. Audio and video travel over WebRTC with DTLS-SRTP, directly between the two devices or through a TURN relay. The server carries only the signaling. It cannot read the signaling, because every `payload` is an MLS message, and it cannot swap a DTLS fingerprint without the receiver noticing.

## Frames

The frames (`call.invite`, `call.ring`, `call.answer`, `call.answered`, `call.signal`, `call.decline`, `call.hangup`, `call.ended`, `call.status`), their data, the call states and the end reasons are in [server design §7.3](../superpowers/specs/2026-10-27-server-design.md#73-realtime-calls-turn-lane-c). This document adds only what the server cannot see: what the `payload` holds, and what a receiver does with it.

## What `payload` holds

`payload` is an MLS application message, in base64url, in the group of the one-to-one conversation with that contact. A sender uses the preferred conversation with the contact ([MLS profile](mls-profile.md#groups-and-conversations)) and creates one if none exists.

The plaintext is a `MessageBody` ([README](README.md#message-content-format)) of kind `callsig`. Its `p` object is plan 8's `CallSignalPayload`:

```json
{"v":1,"id":"6f1c0a52-8d3e-4b7a-9c21-5e4d7a0b9f13","at":1767225600000,"k":"callsig","p":{"kind":"offer","callID":"b2c4e8a0-31d5-4f6a-8e07-9a1b3c5d7e2f","sdp":"v=0 …","dtlsFingerprint":"sha-256 AB:CD:EF:…","ceiling":"balanced"}}
```

| Field of `p` | Type | Used by `kind` | Meaning |
|---|---|---|---|
| `kind` | string | all | One of the kinds below |
| `callID` | string | all | The call's ID, a UUID string made by the caller |
| `video` | boolean | `ring` | Whether the caller offers video |
| `sdp` | string | `offer`, `answer`, `renegotiate`, `renegotiateAnswer` | The session description |
| `dtlsFingerprint` | string | `offer`, `answer` | `sha-256 AB:CD:…`, copied from the SDP's `a=fingerprint` line |
| `candidate` | object | `candidate` | `{sdp, sdpMid?, sdpMLineIndex}`: one ICE candidate |
| `ceiling` | string | `ring`, `offer`, `answer`, `modeChange` | The most this device wants to receive: `ultraLow`, `dataSaver`, `balanced` or `high` |
| `videoOn` | boolean | `modeChange` | Whether this device now sends video |

A field that does not apply is left out. A receiver ignores fields it does not know.

| `kind` | Sent in frame | Sent by | Purpose |
|---|---|---|---|
| `ring` | `call.invite` | caller | Rings the callee's devices. It is small enough to fit in a push. |
| `offer` | `call.answer` | the callee device that answers | Its SDP offer, which it prepared while ringing |
| `answer` | `call.signal` | caller | The answer to that offer |
| `candidate` | `call.signal` | either side | One trickled ICE candidate |
| `renegotiate` | `call.signal` | either side | A new offer in mid-call: an ICE restart, or a mode change that alters ptime, FEC or DTX |
| `renegotiateAnswer` | `call.signal` | the other side | The answer to `renegotiate` |
| `modeChange` | `call.signal` | either side | A new `ceiling` or `videoOn` |

## Call setup

The callee makes the offer. A video SDP is usually 3 to 6 KB, and a push can carry at most 3070 bytes, so the ring cannot hold it ([push](push.md#padding)).

1. The caller sends `call.invite` with a `ring` payload. The server rings every device of the callee account and sends a call push to the ones that are offline ([push](push.md)).
2. Each ringing device prepares its offer and starts gathering ICE candidates while it rings. It sends nothing yet.
3. The device that answers sends `call.answer` with an `offer` payload. The other devices get `call.ended` with `answered_elsewhere`.
4. The caller gets `call.answered`, runs the checks below, sets the remote description and sends `call.signal` with an `answer` payload.
5. Both sides trickle candidates with `call.signal`. Ending a call uses `call.hangup` and `call.decline`, which carry no payload.

## What the receiver must check

A receiver runs these checks, in order, on every call payload. A payload that fails one is dropped and never reaches the media engine.

1. **Decryption.** The payload decrypts as an MLS application message with a `callsig` body. Call frames travel outside the inbox, so a payload can be made at an epoch that the device has not reached yet. It then cannot be decrypted, and the call fails ([push](push.md#ios-voip-pushes) says what happens to a ring).
2. **Sender.** The account in the credential identity of the MLS sender is the peer the receiver expects: the account named in `call.ring`, or the callee account for `call.answered` and `call.signal`. A receiver checks the MLS credential, never the `fromAccountID` or `deviceID` that the server sent. Every server-sent `call.signal` also carries `fromDeviceID`, because several callee devices can signal the caller before the answer. The device in the credential identity MUST equal the device that made the offer (`deviceID` in `call.answered`), and a signal whose credential device differs from `fromDeviceID` is dropped.
3. **Call.** `p.callID` equals the `callID` of the frame, and `kind` is allowed in the call's current state.
4. **Fingerprint.** For an `offer` or an `answer`, `dtlsFingerprint` is present and every `a=fingerprint` line in the SDP equals it. Comparison lowercases the hash name and uppercases the hex. In a `renegotiate` or `renegotiateAnswer`, the `a=fingerprint` lines MUST equal the fingerprint that the call started with.

A mismatch at step 4 is an attack or a bug. The receiver MUST abort the call: it never calls `setRemoteDescription` with that SDP, ends the call as `failed`, sends `call.hangup`, and does not retry the same payload.

The fingerprint is trustworthy because it arrived inside an MLS message from the peer. A relay or server that swapped it in the SDP would otherwise sit in the middle of the media. The check is only as strong as the peer's MLS identity: a server that substitutes KeyPackages can pass it ([OPEN-6](../security/threat-model.md#open-6-no-contact-or-device-verification), TRN-T-02).

## Relay policy

The user's IP-hiding setting decides whether a device may offer its own addresses. Each side applies its own setting, because hiding protects its own address ([GOAL §6](../../GOAL.md#call-relay-privacy)).

| Setting | `iceTransportPolicy` |
|---|---|
| `never` | `all` |
| `nonContacts` | `relay` when the peer is not a saved contact (a sender of a message request is not a contact), otherwise `all` |
| `always` | `relay` |

With `relay`, a device gathers and sends only relay candidates. No host or server-reflexive candidate leaves the device. The server's default for new users is `policy.defaultIPHiding` in its info document.

## TURN credentials

A device calls `GET /v1/turn` and gets `{"iceServers":[{"urls":[…],"username":"…","credential":"…"}],"ttl":43200}`, or `calls_disabled`. The URLs and the relay health rules are in the server design.

- **Lifetime: 12 hours** (`ttl` is 43200 seconds).
- **Username:** `<unix expiry>:<8 random hex>`. It never contains an account or device ID.
- **Credential:** `base64(HMAC-SHA1(secret, username))`, standard base64 with padding. This is coturn's shared-secret scheme. The secret is `APP_TURN_SECRET`, on the app server and on every relay.
- **Not bound to a device.** A leaked credential works until it expires ([threat model](../security/threat-model.md#57-turn-and-coturn), TRN-S-01).
- **Reuse:** coturn checks the credential on every allocation refresh, so it must outlive the call (server design §2 item 7). The server forgets an answered call after 4 hours. A device therefore reuses a cached response only while at least 4 hours of its lifetime remain, and fetches a new one otherwise.

## ICE restarts and reconnection

| Moment | What the call does |
|---|---|
| ICE state `disconnected` | Show "Reconnecting" at once. |
| Still disconnected after 2.5 s, or ICE state `failed`, or the network path changes during the call | Restart ICE. GOAL §6 says 2 to 3 seconds. |
| Still disconnected, 5 s after the last restart | Restart ICE again. |
| ICE state `connected` | Clear "Reconnecting". |
| 30 s after the first `disconnected`, with no recovery | Give up: end the call locally as `connectionLost` and send `call.hangup`. |

- The device keeps gathering candidates for the whole call (`gatherContinually`).
- The caller starts a restart by sending `renegotiate` with a new offer, and the callee answers with `renegotiateAnswer`. A callee that needs a restart sends `renegotiate` without `sdp`, which asks the caller to start one.
- If both sides send `renegotiate` at once, the caller's offer wins and the callee rolls back.
- A bitrate-only change needs no renegotiation. The sender just changes its encoder limit.

## Threats

The signaling rows are in [section 5.2](../security/threat-model.md#52-websocket) and the relay rows in [section 5.7](../security/threat-model.md#57-turn-and-coturn) of the threat model. The server still sees that a call happened, between which accounts and when ([OPEN-11](../security/threat-model.md#open-11-the-operator-sees-the-social-graph-and-timing)). Nothing limits how fast a client can send `call.invite` ([OPEN-14](../security/threat-model.md#open-14-no-limits-on-websocket-frames-and-no-http-timeouts)).

## Open points

- **The ICE restart request:** plan 8 says the callee "asks for one with `renegotiate`" but does not define that payload. This document says `renegotiate` without `sdp`.
- **TURN reuse:** plan 8 caches TURN servers until 10 minutes before their `ttl` ends. That is shorter than the 4 hours above, so a long call that starts on cached credentials can lose its relay.
- **Group calls** (Phase 2) will derive SFrame keys from the MLS group and are not covered here.

Test vectors: [turn-credential.json](test-vectors/turn-credential.json).
