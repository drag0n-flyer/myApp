# Push

**Status:** draft for owner review (push gateway, notification extension and call pushes).
**Builds on:** [server design §7.4](../superpowers/specs/2026-10-27-server-design.md#74-push-lane-d) (endpoints, gateway checks, limits, APNs requests); [GOAL.md](../../GOAL.md#push-notification-gateway) §6; [plan 2](../superpowers/plans/2026-10-27-go-server.md) lane D; [plan 6](../superpowers/plans/2026-10-27-client-networking.md#task-l3c-push-handle-sealing-and-registration) (sealing); [plan 7](../superpowers/plans/2026-10-27-client-messaging.md#task-d2-notification-processor-shared-logic) (the extension); [plan 8](../superpowers/plans/2026-10-27-client-calling.md#task-k2-iphone-callkit-pushkit-audio-session--owner-review) (VoIP).

Apple accepts pushes only with the app publisher's key, which the project's push gateway holds. A self-hosted server therefore never sees an APNs token. The device seals its token to the gateway and gives the server only that sealed handle. The server sends the gateway a signed request that names the handle and carries an opaque, padded payload.

```text
device --sealed handle--> server --signed request: handle + padded payload--> gateway --APNs--> device
```

## The sealed handle

The device seals this JSON object to the gateway's public key:

| Key | Value |
|---|---|
| `t` | The APNs device token, as hex; for `fcm`, the FCM registration token as text |
| `k` | `alert`, `voip` or `fcm` |
| `e` | `production` or `sandbox` |
| `b` | The bundle ID of the app that owns the token; for `fcm`, the Android application ID |

```json
{"t":"00ff","k":"alert","e":"sandbox","b":"org.example.app"}
```

- **Suite:** the shared HPKE suite in the [README](README.md#conventions), with `info = "push-handle-v1"`.
- **Wire layout:** `enc (32 bytes) ‖ ciphertext`, so the sealed handle is `32 + len(plaintext) + 16` bytes.
- **Upload:** `PUT /v1/push/tokens/{kind}` with `{"sealed": "<base64url>"}`. The server stores the bytes as received and cannot read them. The `k` inside the handle is the one the gateway enforces.
- **Where the key comes from:** the gateway's public key and URL are compiled into the app. If a server's info document names a different gateway, the app registers no push tokens with that server. A dishonest server therefore cannot collect raw tokens through a fake gateway (plan 6, decision 3).
- **Bundle IDs:** the iPhone app and the Mac app each have their own, and the gateway allows only the ones it is configured with (`PUSHGW_BUNDLE_IDS`). A VoIP push goes to the topic `<bundle>.voip`.
- **Android:** an `fcm` handle is `{"t":"<FCM token>","k":"fcm","e":"production","b":"<applicationId>"}`. The gateway allows only the application IDs in `PUSHGW_ANDROID_APP_IDS`, refuses `fcm` handles when it has no FCM key, and requires `e` to be `production` for them. A `message` push needs an `alert` or `fcm` handle; a `call` push may use `voip`, `alert` or `fcm`.

## Padding

The payload that the server hands to push is the same bytes as the inbox item's `payload`, or a call payload. Padding hides its size from the gateway and from Apple, within a bucket.

- **Layout:** a 2-byte big-endian length, then the payload, then zero bytes up to the smallest bucket that fits. The buckets are 256, 512, 1024, 2048 and 3072 bytes.
- **A payload over 3070 bytes** is replaced by an empty one (length 0, padded to 256 bytes). The device then fetches its inbox.
- **Length 0 means "fetch the inbox".**
- **Unpadding is strict.** The data must be exactly one of the five bucket sizes, the length must fit, and every padding byte must be zero. Anything else is malformed.

| Payload length | Padded length |
|---|---|
| 0 | 256 |
| 254 | 256 |
| 255 | 512 |
| 1000 | 1024 |
| 3070 | 3072 |
| 3071 (replaced by an empty payload) | 256 |

The padded bytes travel unchanged through the gateway to the device, where the notification extension unpads them. The gateway does not unpad.

**FCM exception:** FCM data messages are limited to 4096 bytes, and the 3072 bucket is 4096 characters in base64. For an `fcm` handle the gateway therefore replaces a padded payload over 2048 bytes by the 256-byte empty one (length 0), and the device fetches its inbox. The decision uses only the padded length, which the gateway already sees. The 2048 bucket (2732 characters) is forwarded unchanged.

## Server to gateway

The server sends `POST {gateway}/v1/push` with the body `{"handle": "<base64>", "kind": "message" or "call", "data": "<base64 of the padded payload>", "nonce": "<text>"}`. `handle` and `data` are **standard base64 with padding** (Go's default for a byte string in JSON), and `nonce` is optional text that makes every body, and so every signature, unique within one second. The gateway ignores it. The base64url rule of the [README](README.md#conventions) covers client-facing fields only, and this request is not one. The endpoints, the gateway's registration and its replies are in the server design.

Three headers authenticate the request:

| Header | Value |
|---|---|
| `X-Server-ID` | The ID the gateway gave this server at registration |
| `X-Timestamp` | The sender's clock, in decimal Unix seconds |
| `X-Signature` | An Ed25519 signature over the signing string below, in standard base64 with padding |

The signing string is five lines joined by `\n`, with no trailing newline:

```text
v1
POST
/v1/push
<timestamp>
<lowercase hex of the SHA-256 of the exact request body bytes>
```

- The method is upper case. The path is the request path only: no host and no query.
- The timestamp is the same text as the `X-Timestamp` header.
- The signing string is signed as its UTF-8 bytes with plain Ed25519 (RFC 8032).
- For method `POST`, path `/v1/push`, timestamp `1767225600` and body `{}`, the signing string ends in `44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`.
- If the gateway answers `410`, the server deletes that handle.

## Gateway checks and limits

The gateway's checks before it sends, the order they run in, and its limits per server and per handle are in [server design §7.4](../superpowers/specs/2026-10-27-server-design.md#74-push-lane-d). This document adds none. The gateway can read each handle it opens, so it sees raw tokens and bundle IDs at send time ([OPEN-13](../security/threat-model.md#open-13-push-gateway-key-custody)).

## What the notification extension does with `d`

APNs delivers `d`: standard base64 with padding of the **padded** payload, exactly the bytes the server sent. The gateway validates the padding but forwards the padded bytes unchanged. For a message, the alert push has `mutable-content`, so the notification extension runs before anything is shown.

1. Decode `d` and unpad it. If that fails, show the generic text from the push (`[App]`, "New message").
2. **Empty payload:** fetch the inbox, at most 20 items, and show the newest stored message, or the generic text.
3. **Otherwise,** take the cross-process lock. If it stays busy for 2 seconds, show the generic text.
4. Drop a payload whose SHA-256 is already marked as processed. The push payload is the same bytes as the inbox payload, so the extension and the app never process one MLS message twice. The app, not the extension, acknowledges inbox items on the server.
5. MLS-process the payload ([MLS profile](mls-profile.md#commits)) and apply it as a `MessageBody`:
   - a message from a contact shows the contact's name and a preview
   - a message from someone who is not a contact shows "Message request"
   - a muted or blocked chat, a receipt, an edit or a delete shows nothing, but only when the notification-filtering entitlement is on. Otherwise the generic text is shown.
   - a `callsig` of kind `ring` becomes the incoming-call notification on the Mac
6. After a 25-second deadline, or on any error, show the generic text.

The extension's memory budget is about 24 MB. The visible alert text is not authenticated: it is whatever the sender of the push chose, and only text that decrypts under MLS is trusted ([threat model](../security/threat-model.md#59-notification-service-extension), NSE-S-01).

## Android pushes through FCM

The gateway sends an `fcm` handle as an FCM HTTP v1 data message: `{"message":{"token":"<t>","data":{"k":"message"|"call","d":"<std base64 of the padded payload>"},"android":{"priority":"HIGH","ttl":"86400s"|"45s"}}}`. A call expires after 45 seconds (the ring timeout), a message after one day. FCM `UNREGISTERED` becomes `410` to the server, which deletes the handle; any other `404`, including `NOT_FOUND`, is a gateway error (`502`) and keeps the handle. The gateway authenticates with an OAuth access token minted from the owner's service-account key (RS256 JWT, cached until 5 minutes before expiry); it follows no redirects and logs neither tokens nor payloads. Details: [server design §7.4](../superpowers/specs/2026-10-27-server-design.md#74-push-lane-d).

## Android pushes through UnifiedPush

A device that uses UnifiedPush uploads `PUT /v1/push/tokens/unifiedpush {"endpoint":"https://...","p256dh":"<b64url>","auth":"<b64url>"}` instead of a sealed handle. The server must read it, so it is not sealed. The endpoint is at most 1024 characters, `https` only, port 443 only, with no user info. `p256dh` is the receiver's uncompressed P-256 public key (65 bytes) and `auth` is its 16-byte Web Push secret. The server refuses the upload with `bad_request` when it has no VAPID key. A device has one transport: uploading `unifiedpush` deletes its `fcm` handle and the reverse.

The server sends directly, with no gateway: `POST <endpoint>` with the body encrypted as RFC 8291 `aes128gcm` (one record), and these headers:

| Header | Value |
|---|---|
| `Content-Encoding` | `aes128gcm` |
| `TTL` | `45` for a call, `86400` for a message |
| `Urgency` | `high` |
| `Authorization` | `vapid t=<JWT>, k=<server public key>` (RFC 8292: ES256, `aud` is the endpoint's origin, `exp` 12 hours out, `sub` is `https://<host>`) |

The plaintext is one kind byte (`0x01` message, `0x02` call) followed by the padded payload (see Padding; the device unpads it, the distributor sees only ciphertext). The server's VAPID public key is `push.vapidKey` in `/v1/server` (base64url, 65 bytes), from `APP_VAPID_KEY_FILE` (`appserver keygen vapid`). `404` and `410` delete the registration; other failures are errors that keep it.

The endpoint is untrusted. The server refuses any address that is private, loopback, link-local, multicast, unspecified or in 100.64.0.0/10 (including IPv4-mapped IPv6 forms and the cloud metadata address), checked on the resolved address when it connects, so DNS rebinding does not help. It follows no redirects and uses no proxy. It has 10-second timeouts, and reads at most 16 KiB of the answer. It logs no endpoints, keys or payloads.

## iOS VoIP pushes

An iPhone gets call pushes as PushKit VoIP pushes with `apns-expiration: 0`. The server picks the device's `voip` handle for a call push, or its `alert` handle when it has no `voip` one, which is the case on a Mac. A Mac never gets a VoIP push: its call push is an alert push, and the extension turns it into the call notification.

**Every VoIP push MUST be reported to CallKit immediately**, before the push handler returns. This holds for a push that cannot be decrypted, an empty one, a call that has already ended and a call from a stranger. The app reports the call and then ends it at once. If an iPhone does not report, iOS kills the app and stops delivering VoIP pushes to it.

- Before iOS 26.4 every VoIP push is reported.
- On iOS 26.4 and later, the app reports whenever the system says it must (`mustReport`). When `mustReport` is true, nothing is ignored.
- Hang-ups and call state travel over the live connection, not by push.

## Threats

The rows are in the threat model's [section 5.5](../security/threat-model.md#55-push-gateway) (gateway), [5.6](../security/threat-model.md#56-apns-path) (APNs) and [5.9](../security/threat-model.md#59-notification-service-extension) (extension).

## Open points

- **Plan 2 D3 and the padding:** its test says the fake APNs receives "the unpadded data". The server design and plans 4, 7 and 8 have the device unpad, so the gateway forwards padded bytes. Unpadding at the gateway would show Apple the true size and make the extension reject the payload.
- **Encodings (decided by the owner, 2026-10-28):** the spec follows the code. `handle`, `data`, `X-Signature` and `d` are standard base64 with padding, which is what Go's `[]byte` JSON encoding and `pushproto.Sign` produce. Only client-facing fields are base64url without padding.
- **Gateway key custody:** replacing the gateway's HPKE key needs an app release ([OPEN-13](../security/threat-model.md#open-13-push-gateway-key-custody)).

Test vectors: [push-padding.json](test-vectors/push-padding.json), [request-signature.json](test-vectors/request-signature.json), [sealed-handle.json](test-vectors/sealed-handle.json).
