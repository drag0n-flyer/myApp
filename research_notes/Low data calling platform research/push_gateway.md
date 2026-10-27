# Push Gateway Architecture for a Self-Hosted E2EE Messaging and Calling App (iOS/macOS first, Android later); notes as of 2026-10-27

Scope: how independently run servers (written in Go) can wake one App Store app; APNs/iOS rules; how existing projects do it; costs; recommended design. Sources were fetched on 2026-10-27 unless noted. Apple doc URLs point to the canonical pages; their content was read through Apple's JSON doc endpoints.

## 1. APNs constraints: who can push, auth, payloads, headers, throttling, cost. Do self-hosted servers need a project-run gateway?

### Takeaway
Only the holder of the app publisher's APNs credentials (.p8 key + Team ID, or a certificate) can push to the app. That key works for the whole team. Handing it to third-party operators would let any of them push arbitrary visible alerts to every user. So self-hosted servers must go through a project-run gateway. Every comparable project (Matrix/Element, Zulip, Mattermost, Rocket.Chat, Nextcloud, Delta Chat, ntfy) does this. APNs itself has no per-push fee; the only Apple cost is the $99/yr Developer Program.

### Cited Findings
- **Token auth (.p8):** Apple issues a 10-character Key ID and a `.p8` signing key. The JWT uses ES256 only, with `kid` = Key ID and `iss` = 10-character Team ID. — [Apple: Establishing a token-based connection](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
- **Token refresh:** refresh "no more than once every 20 minutes and no less than once every 60 minutes". A token whose `iat` is more than 1 hour old gets `ExpiredProviderToken (403)`. Refreshing too often gets `TooManyProviderTokenUpdates`. — [Apple token auth](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns); [Apple: Handling responses from APNs](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns)
- **One token, many servers; one team per connection:** "You can use the same token from multiple provider servers". "APNs doesn't support authentication tokens from multiple developer accounts over a single connection". APNs "relates a team ID and associated bundle IDs to a connection on the first push." — [Apple token auth](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)
- **Transport:** HTTP/2 + TLS 1.2+, HPACK required, and HTTP/2 PRIORITY frames are ignored. There are separate development and production endpoints. — [Apple: Sending notification requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- **Payload limits:** 4 KB (4096 bytes) for normal notifications and 5 KB (5120 bytes) for VoIP. Compressed JSON is not allowed. — [Apple: Sending notification requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns); [Apple: Generating a remote notification](https://developer.apple.com/documentation/usernotifications/generating-a-remote-notification)
- **Headers:**
  - `apns-topic` (required, the bundle ID; suffixed for some types).
  - `apns-push-type`: "required for watchOS 6+; recommended for macOS, iOS". Apple says "send an apns-push-type header with each push" and the value must match the payload. Values include alert, background, voip, location, liveactivity, fileprovider, complication, controls, mdm, pushtotalk.
  - `apns-priority` (10 / 5 / 1); `apns-expiration`; `apns-collapse-id`; `apns-id` (UUID).
  - — [Apple: Sending notification requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- **Delivery behaviour:** "Notifications with apns-priority 5 and apns-priority 1 might get grouped and delivered in bursts." "Your notifications may also get throttled, saved in storage, and in some cases, not delivered." APNs may store an undeliverable notification for up to 30 days, depending on `apns-expiration`. — [Apple: Sending notification requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns)
- **Rate errors:**
  - `429 TooManyRequests`: "Too many requests were made consecutively to the same device token."
  - `410 Unregistered`/`ExpiredToken`: stop sending to that token.
  - `413 PayloadTooLarge`.
  - Never retry `BadDeviceToken`, `DeviceTokenNotForTopic`, `Forbidden`, `ExpiredToken`, `Unregistered` or `PayloadTooLarge`.
  - APNs disconnects providers that cause too many error conditions (410 does not count).
  - — [Apple: Handling responses from APNs](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns)
- **Background (silent) pushes:** `content-available` only, `apns-push-type: background`, `apns-priority: 5`. They are low priority with no delivery guarantee and are throttled when excessive: "don't try to send more than two or three per hour." The app gets 30 s. If the app was force-quit, held notifications are discarded. — [Apple: Pushing background updates](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)
- **Cost:** Apple Developer Program is 99 USD per membership year (Enterprise 299 USD); prices may vary by region. — [Apple: Enroll](https://developer.apple.com/programs/enroll/); [Apple: Purchase and activation](https://developer.apple.com/support/purchase-activation/)
- **Gateway requirement in practice:** Nextcloud states that its store apps "are signed with Nextcloud developer keys or certificates", so it created push-notifications.nextcloud.com. Rocket.Chat: "The official mobile apps only work with the Rocket.Chat gateway". Mattermost HPNS/TPNS "only work with the Mattermost Apple App Store and Google Play apps", and custom-built apps need their own proxy. — [Nextcloud push-v2](https://github.com/nextcloud/notifications/blob/master/docs/push-v2.md); [Rocket.Chat push docs](https://docs.rocket.chat/docs/push-notifications); [Mattermost HPNS docs](https://docs.mattermost.com/deploy/mobile-hpns.html)

### Inferences
- The .p8 key is team-wide: one key serves all of the team's apps and all push types. Sharing it with server operators is out of the question. A project-run gateway is mandatory for App Store builds. The only alternatives are operators shipping their own white-labelled app, or LAN-only Local Push Connectivity (section 8).
- Throttling is per device token (429) and per app/device budget (background pushes). The gateway must coalesce bursts per token (for example with `apns-collapse-id`) and should not use `background` pushes as the main wake mechanism.

### Gaps
- Apple publishes no numeric global or per-provider rate limit for APNs; only per-token 429 and the "2–3 background pushes/hour" guidance were found.
- No Apple page fetched states "APNs is free" in so many words. Free use is inferred from the absence of any push pricing; the program fee is the only documented charge.

## 2. How existing projects solve it (Sygnal, Rocket.Chat, Mattermost, Nextcloud, Zulip, Delta Chat/chatmail, Signal, Element X, ntfy): design, metadata seen, pricing, licences, reuse

### Takeaway
There are two patterns:
- **"Ping + fetch":** Signal, Element X with Matrix `event_id_only`, ntfy, Delta Chat. The push carries no content and the device fetches and decrypts.
- **"E2EE payload in push":** Zulip 12.0+ (April 2026) and Nextcloud.

Zulip's 2026 design is the most privacy-complete model to copy:
- The device token is sealed to the gateway, so the self-hosted server never sees it.
- There is a per-device symmetric key, and the gateway sees only a token, an opaque key id, the ciphertext and the priority.
- Servers register and are rate-limited.

Mattermost's push proxy is the only one written in Go (Apache-2.0) and already supports VoIP pushes, so it is the best fork or reference candidate.

### Cited Findings
**Matrix Push Gateway API + Sygnal**
- The homeserver POSTs `/_matrix/push/v1/notify`. In `event_id_only` format only `event_id`, `room_id`, `counts` and `devices` are required. The full format can include `content`, `sender_display_name`, `room_name` and `prio`. The spec marks the endpoint "Rate-limited: No; Requires authentication: No". — [Matrix spec: Push Gateway API](https://spec.matrix.org/latest/push-gateway-api/)
- Sygnal (reference gateway, Python ≥3.8):
  - Supports `apns` (certificate, or token auth with `keyfile` p8 + `key_id` + `team_id` + `topic`, plus a configurable `push_type`) and `gcm` (FCM). It can route via an HTTP CONNECT proxy.
  - Licence: "GNU Affero General Public License v3 OR a paid-for Element Commercial License".
  - — [Sygnal README](https://github.com/element-hq/sygnal/blob/main/README.md)
- Element X iOS:
  - Its NSE target has the `com.apple.developer.usernotifications.filtering` entitlement and processes `processEvent(eventID, roomID:)`, meaning it fetches and decrypts the event in the NSE.
  - Code comments note that with the filtering entitlement, exiting early "results in the notification being discarded".
  - The app logs NSE memory usage.
  - Licence: AGPL-3.0.
  - — [element-x-ios NSE target.yml](https://github.com/element-hq/element-x-ios/blob/develop/NSE/SupportingFiles/target.yml); [NotificationServiceExtension.swift](https://github.com/element-hq/element-x-ios/blob/develop/NSE/Sources/NotificationServiceExtension.swift); [LICENSE](https://github.com/element-hq/element-x-ios/blob/develop/LICENSE)

**Zulip Mobile Push Notification Service ("bouncer")**
- **E2EE since Zulip Server 12.0 (April 2026):** content and metadata (sender, recipient, channel, topic) are not visible to Apple, Google or the service. Admins can require E2EE. — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html)
- **What the gateway sees per notification:**
  - the APNs/FCM token, which the Zulip server never sees in plaintext;
  - an opaque `push_key_id`;
  - an XSalsa20-Poly1305 ciphertext;
  - the priority (higher for a new message, lower for a removal).
  - It stores tokens, basic organisation metadata, optional usage stats and aggregate push counts per server.
  - — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html)
- **Crypto:**
  - Each payload uses libsodium `crypto_secretbox_easy` with a per-device 256-bit key and a fresh 192-bit nonce.
  - At registration the device token is wrapped with `crypto_box_seal` (Curve25519) so the server can forward it without learning it.
  - A 1-byte algorithm prefix allows cipher migration.
  - Limitation: incomplete forward secrecy, because keys are rotated about every 30 days.
  - — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html)
- **Registration, rate limit, pricing, licence:**
  - Servers register via `manage.py register_server` (hostname + contact email, accept ToS).
  - The rate limit is 1000 requests/min per server, as DoS protection.
  - Organisations with more than 10 users must buy a plan. Free for up to 10 users, and unlimited for eligible communities.
  - Self-hosted Basic is $3.50/user/month. Business is $6.67/user/month annual or $8 monthly, with a 25-user minimum (prices as of 2026-10-27).
  - The bouncer code is in the Apache-2.0 Zulip repo.
  - — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html); [Zulip plans](https://zulip.com/plans/); [Zulip LICENSE](https://github.com/zulip/zulip/blob/main/LICENSE)

**Mattermost Push Proxy (HPNS/TPNS)**
- It is written in Go and licensed Apache-2.0. On `transport=voip` it emits `apns-push-type: voip`, topic `<ApplePushTopic>.voip` and a minimal payload, reusing the same APNs key. — [mattermost-push-proxy README](https://github.com/mattermost/mattermost-push-proxy/blob/master/README.md); [LICENSE.txt](https://github.com/mattermost/mattermost-push-proxy/blob/master/LICENSE.txt)
- **HPNS** (production) needs a Professional/Enterprise subscription. **TPNS** is free but "not recommended for production" and has no SLA. Mattermost's docs conflict on whether TPNS is "all plans" or "non-commercial self-hosted" only. — [Mattermost HPNS docs](https://docs.mattermost.com/deploy/mobile-hpns.html)

**Rocket.Chat**
- The gateway requires workspace registration and acceptance of the Cloud Service Privacy Terms, with outbound access to gateway.rocket.chat. Turning off the gateway means shipping your own white-labelled app. — [Rocket.Chat push docs](https://docs.rocket.chat/docs/push-notifications)
- Community workspaces get 10,000 free pushes/month and paid plans are "unlimited". Pushes over the quota are not delivered until the monthly reset. One forum report says a Starter workspace hit the 10,000 cap despite "unlimited"; this conflict is unresolved. — [Rocket.Chat push docs](https://docs.rocket.chat/docs/push-notifications); [Rocket.Chat push FAQ](https://docs.rocket.chat/docs/push-notifications-faq); [forum report](https://forums.rocket.chat/t/starter-version-push-notification-limit-error/21929)

**Nextcloud push proxy (push-notifications.nextcloud.com)**
- Registration:
  - The device creates an RSA-2048 key pair and sends `devicePublicKey` plus a SHA-512 `PushTokenHash`, but not the token, to the Nextcloud server.
  - It then sends the real `pushToken`, `deviceIdentifier`, a signature and the user public key directly to the proxy.
- Delivery:
  - The server encrypts the subject with the device public key and signs it.
  - The proxy verifies the signature with the user public key.
  - "Google and Apple don't even know the Nextcloud server sending the notification."
- The push payload is the placeholder `NEW_NOTIFICATION` plus encrypted `subject` and `signature`. Licence of the notifications app is AGPL-3.0.
- — [Nextcloud push-v2.md](https://github.com/nextcloud/notifications/blob/master/docs/push-v2.md)

**Delta Chat / chatmail notification proxy**
- It is a central service at notifications.delta.chat, a "small Rust program that forwards device tokens" to APNs/FCM. It is configured with an APNs `.p12` certificate, an FCM key, an **OpenPGP keyring** and a **VAPID** key (Web Push). The repo carries an MIT licence file. — [chatmail/notifiers README](https://github.com/chatmail/notifiers); [LICENSE-MIT](https://github.com/chatmail/notifiers/blob/main/LICENSE-MIT)

**Signal**
- Signal-Server's `APNSender` (Pushy library) sends:
  - **urgent** messages as `PushType.ALERT`, priority IMMEDIATE, payload `{mutable-content:1, loc-key:"APN_Message"}` with no message data;
  - **non-urgent** messages as `PushType.BACKGROUND`, CONSERVE_POWER, `content-available:1`.
  - The NSE then fetches and decrypts.
  - Licence: AGPL-3.0.
  - — [Signal-Server APNSender.java](https://github.com/signalapp/Signal-Server/blob/main/service/src/main/java/org/whispersystems/textsecuregcm/push/APNSender.java); [Signal-Server LICENSE](https://github.com/signalapp/Signal-Server/blob/main/LICENSE)

**ntfy (self-hosted → iOS)**
- A self-hosted ntfy server sets `upstream-base-url: https://ntfy.sh`. On publish it sends a `poll_request` carrying only the message ID (`X-Poll-ID`) to a SHA-256-hashed topic on ntfy.sh, which relays via APNs. The iOS app then fetches the message from the origin server. Without the relay, iOS only gets notifications in the foreground. Licence: Apache-2.0. — [ntfy config docs](https://docs.ntfy.sh/config/); [secondary: StackHarbor guide](https://stackharbor.com/en/knowledge-base/ntfy-self-hosted-push-alerts/); [ntfy LICENSE](https://github.com/binwiederhier/ntfy/blob/main/LICENSE)

### Inferences
- **Licence fit for a Go project:**

  | Project | Language | Licence | Fit |
  |---|---|---|---|
  | Mattermost push proxy | Go | Apache-2.0 | Easiest to fork/vendor |
  | chatmail notifiers | Rust | MIT | Permissive, different language |
  | Zulip bouncer | Python/Django, embedded in monolith | Apache-2.0 | Permissive, hard to extract |
  | Sygnal | Python | AGPL-3.0/commercial | Copyleft; network-service obligations if modified |
  | Signal-Server, Element X | Java / Swift | AGPL-3.0 | Copyleft |

- A purpose-built Go gateway is small: an HTTP/2 APNs client, an FCM v1 client, auth and a rate limiter. Writing it is recommended over adopting Sygnal (Python, AGPL, Matrix-specific schema, unauthenticated endpoint). Copy Zulip's crypto and registration model, and use Mattermost's code as a reference for APNs/VoIP handling.
- Business models found: free-with-cap (Zulip 10 users, Rocket.Chat 10k/month), paid-for-production (Mattermost HPNS), and free/donation (Nextcloud, Delta Chat, ntfy). This suggests a free gateway with per-server quotas is viable and conventional.

### Gaps
- Sygnal's licence history (it was Apache-2.0 before the Element AGPL relicensing) and Rocket.Chat server/gateway licence were not verified this session; the GitHub API was rate-limited.
- Nextcloud's proxy server source and licence were not checked.
- How chatmail uses the OpenPGP key (presumably clients encrypt tokens to the proxy so relays can't read them) was not confirmed beyond the README configuration.
- Signal's iOS client NSE behaviour was inferred from the server payload, not from client code.

## 3. iOS mechanics for E2EE: Notification Service Extension, limits, filtering entitlement, background pushes, content privacy

### Takeaway
Use alert pushes with `mutable-content: 1` and a generic placeholder alert. The NSE decrypts, or fetches and decrypts, and rewrites the content. The NSE runs only for alert-type pushes and has roughly 30 s and roughly 24 MB; those numbers come from Apple DTS forum posts, not formal docs. Apply for the filtering entitlement so dummy, duplicate or stale pushes can be silently dropped. Do not rely on background pushes, which are throttled to about 2–3/hour and dropped if the user force-quit the app.

### Cited Findings
- **When the NSE runs:** only if the push is configured to display an alert and `aps` has `mutable-content: 1`. It "has a limited amount of time" to call the completion handler, and `serviceExtensionTimeWillExpire` is the fallback. Available on iOS 10+ and **macOS 10.14+**. — [Apple: UNNotificationServiceExtension](https://developer.apple.com/documentation/usernotifications/unnotificationserviceextension)
- **Limits:** Apple DTS replies on the forums cite about 30 seconds and 24 MB total memory "as of this writing"; another post cites 25 MB. Image work can crash on larger-screen devices because of scale factor. — [Apple Forums: NSE memory limits](https://developer.apple.com/forums/thread/64634); [Apple Forums: NSE memory between invocations](https://developer.apple.com/forums/thread/722879)
- **Filtering entitlement (`com.apple.developer.usernotifications.filtering`):**
  - Lets the NSE "receive remote notifications without displaying the notification to the user".
  - It must be applied for (request form).
  - To suppress, pass an empty `UNNotificationContent` with no title, body, sound or attachments.
  - The push **must** use `apns-push-type: alert`, "otherwise, the system always displays the notification banner."
  - — [Apple: filtering entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.usernotifications.filtering)
- Element X ships this entitlement in its NSE; Signal uses the same pattern (generic localized "APN_Message" + mutable-content). — [element-x-ios target.yml](https://github.com/element-hq/element-x-ios/blob/develop/NSE/SupportingFiles/target.yml); [Signal APNSender](https://github.com/signalapp/Signal-Server/blob/main/service/src/main/java/org/whispersystems/textsecuregcm/push/APNSender.java)
- **Background pushes:** low priority, not guaranteed, throttled ("two or three per hour"), 30 s execution, discarded if force-quit. — [Apple: Pushing background updates](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)
- **Payload keys:** the `aps` dictionary supports `interruption-level` (passive/active/time-sensitive/critical), `relevance-score`, `mutable-content` and `content-available`. — [Apple: Generating a remote notification](https://developer.apple.com/documentation/usernotifications/generating-a-remote-notification)

### Inferences
- **Content-in-push versus ping-and-fetch.** For a low-data app, embedding the E2EE ciphertext (under about 3.5 KB after base64 and the `aps` envelope) avoids an NSE network round trip on slow or metered links. It also reduces the chance of hitting the roughly 30 s budget. Use ping-and-fetch only for larger messages.
- **Placeholder text.** The visible fallback (shown if the NSE crashes or times out) must be generic, for example "New message". Never include sender or room names.
- **NSE footprint.** Keep the NSE small: one crypto library and no heavy SDK. Element X's memory logging shows this is a real operational risk.

### Gaps
- No formal Apple documentation states the 24 MB / 30 s numbers; they come from DTS forum posts and may change per OS.
- Approval criteria and timelines for the filtering entitlement are not public.
- Whether macOS NSE limits match iOS was not found.

## 4. VoIP calls: PushKit/CallKit rules, consequences, encrypted call pushes, China, LiveCommunicationKit, macOS

### Takeaway
Since the iOS 13 SDK, every PushKit VoIP push must be reported to CallKit (or, since iOS 17.4, LiveCommunicationKit) before the delegate's completion handler runs. Otherwise iOS kills the app and may stop delivering VoIP pushes.

iOS 26.4 added `PKVoIPPushMetadata.mustReport`, which signals per push whether reporting is required. This is new in 2026 and the semantics should be verified. Encrypted call pushes must therefore report a placeholder call immediately and decrypt or update the caller after.

China requires CallKit to be disabled; this is a 2018 MIIT/App Review rule, and its current status is unverified. Native macOS has no CallKit and no VoIP push type, so use alert pushes there.

### Cited Findings
- **Reporting rule:**
  - "When linking against the iOS 13 SDK or later, your implementation… must report notifications of type voIP to the CallKit framework."
  - "On iOS 13.0 and later, if you fail to report a call to CallKit, the system will terminate your app. Repeatedly failing to report calls may cause the system to stop delivering any more VoIP push notifications."
  - "If you want to initiate a VoIP call without using CallKit, register for push notifications using the User Notifications framework instead of PushKit."
  - — [Apple: pushRegistry(_:didReceiveIncomingPushWith:for:completion:)](https://developer.apple.com/documentation/pushkit/pkpushregistrydelegate/pushregistry(_:didreceiveincomingpushwith:for:completion:))
- **Apple guidance:**
  - "If you are unable to support CallKit in your app, you cannot use PushKit."
  - Set `apns-expiration` to 0 or a few seconds.
  - Include a call identifier so the app can check in with the server.
  - Connect to the VoIP server in parallel with reporting to CallKit; update CallKit later.
  - — [Apple: Responding to VoIP notifications from PushKit](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit)
- **VoIP payload and header:** max 5120 bytes, `apns-push-type: voip`, topic `<bundle>.voip`. The same .p8 key works for VoIP (Mattermost reuses it). — [Apple: Sending requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns); [mattermost-push-proxy README](https://github.com/mattermost/mattermost-push-proxy/blob/master/README.md)
- **New in iOS 26.4:** `PKVoIPPushMetadata` / `mustReport`, "A boolean indicating whether a VoIP app must report a call or live conversation in response to receiving this VoIP notification." It is available on iOS, iPadOS, Mac Catalyst, macOS, visionOS and watchOS 26.4. — [Apple: PKVoIPPushMetadata.mustReport](https://developer.apple.com/documentation/pushkit/pkvoippushmetadata/mustreport)
  - A third-party guide claims `mustReport == false` means no report is required. This is not confirmed by Apple text fetched. — [Sinch push docs](https://developers.sinch.com/docs/in-app-calling/ios/push-notifications)
- **LiveCommunicationKit:**
  - Available on iOS/iPadOS/Mac Catalyst 17.4+, visionOS 1.1+, watchOS 10.4+.
  - "Initiate and receive VoIP conversations… use your app as the default dialer or calling app."
  - `ConversationManager.reportNewIncomingConversation(uuid:update:)` rings the device.
  - — [Apple: LiveCommunicationKit](https://developer.apple.com/documentation/livecommunicationkit); [reportNewIncomingConversation](https://developer.apple.com/documentation/livecommunicationkit/conversationmanager/reportnewincomingconversation(uuid:update:))
  - Developer reports (2025) of background crashes when replacing CallKit with LiveCommunicationKit in the PushKit path. — [Apple Forums thread 775348](https://developer.apple.com/forums/thread/775348)
- **Platforms:** `CXProvider` (CallKit) lists iOS 10, Mac Catalyst, visionOS and watchOS 9, but **not native macOS**. `PKPushType.voIP` lists iOS 9, **Mac Catalyst 14** and visionOS/watchOS, but not native macOS. NSE is available on macOS 10.14+. — [Apple: CXProvider](https://developer.apple.com/documentation/callkit/cxprovider); [Apple: PKPushType.voIP](https://developer.apple.com/documentation/pushkit/pkpushtype/voip); [Apple: UNNotificationServiceExtension](https://developer.apple.com/documentation/usernotifications/unnotificationserviceextension)
- **China:**
  - In 2018 Apple App Review issued Guideline 5.0 rejections saying the MIIT "requested that CallKit functionality be deactivated in all apps available on the China App Store". VoIP calling itself remained allowed without CallKit.
  - About two dozen apps were pulled until CallKit was removed or disabled in China.
  - Vendors detect China at runtime and disable CallKit, and sometimes must appeal automated rejections.
  - — [TechCrunch 2018](https://techcrunch.com/2018/05/25/googles-duo-and-ciscos-webex-teams-among-the-voip-apps-pulled-from-the-china-app-store); [Apple Forums 103083](https://developer.apple.com/forums/thread/103083); [CounterPath: CallKit in China](https://support.counterpath.com/hc/callkit-in-china)

### Inferences
- **Encrypted call push design.** The VoIP payload carries an opaque call ID plus an encrypted blob (caller, SDP/session keys if small). The app must call `reportNewIncomingCall` synchronously with a generic handle ("Incoming call") before decrypting. It then decrypts (keys are in an app-group keychain) and calls `reportCall(with:updated:)` with the real caller name.
- **Never send a VoIP push for anything other than a ring.** Hang-up and cancel must go over the app's live connection, or as an alert push that the NSE filters.
- **Cancel race.** The server should check, before sending, that the call is still ringing; this avoids the "cancelled before delivered" case.
- **China fallback.** Use a time-sensitive alert push plus NSE, which then rings via a local notification sound; there is no lock-screen call UI. Gate this by region detection. Make the gateway pick the push type per device registration (`voip_capable: false`), not per server.
- **Native macOS (non-Catalyst).** Incoming calls must use alert pushes and NSE/local notifications, and the gateway must support that path anyway.

### Gaps
- Whether the 2018 China CallKit rule is still enforced in 2026, and whether it extends to LiveCommunicationKit. No source newer than 2018 was found.
- Exact Apple semantics of when `mustReport` is false (for example foreground or a recently reported call) were not found in Apple's text.
- LiveCommunicationKit regional availability and default-calling-app rules were not researched.

## 5. Android later: FCM, UnifiedPush + ntfy, supporting both

### Takeaway
FCM is free. Send it from the same gateway as encrypted, padded data messages. UnifiedPush uses standard Web Push (RFC 8030/8291) endpoints, so self-hosted servers can push directly to the user's chosen distributor (ntfy, Sunup, NextPush…) with no project gateway at all. Support both by modelling a device's push target as either a gateway handle or a Web Push endpoint.

### Cited Findings
- Firebase pricing lists "Cloud Messaging (FCM): No-cost" on both the Spark and Blaze plans. — [Firebase pricing](https://firebase.google.com/pricing)
- **UnifiedPush:**
  - Decentralised push where the user chooses the distributor, and "Push notifications are encrypted".
  - Distributors include Sunup, ntfy, Conversations (XMPP), NextPush, gCompat-UP (FCM-backed) and KUnifiedPush (Linux).
  - The endpoint is a Web Push (RFC 8030) Push Resource URL.
  - — [UnifiedPush](https://unifiedpush.org/); [UnifiedPush spec definitions](https://unifiedpush.org/developers/spec/definitions/)
- **Licences:** UnifiedPush android-connector is Apache-2.0; ntfy is Apache-2.0 and self-hostable. — [android-connector LICENSE](https://github.com/UnifiedPush/android-connector/blob/main/LICENSE); [ntfy LICENSE](https://github.com/binwiederhier/ntfy/blob/main/LICENSE)
- Sygnal and chatmail notifiers already multiplex APNs + FCM, and chatmail also does Web Push with VAPID, in one gateway. — [Sygnal README](https://github.com/element-hq/sygnal/blob/main/README.md); [chatmail/notifiers](https://github.com/chatmail/notifiers)

### Inferences
- **Server-side model:** `PushTarget{kind: "gateway", platform: apns|apns-voip|fcm, sealed_handle}` or `PushTarget{kind: "webpush", endpoint, p256dh, auth}`. The Go server encrypts the same inner E2EE payload either way. Go has Web Push (RFC 8291) libraries, but none was checked here.
- **FCM settings:** use high-priority **data** messages, not notification messages, so the app decrypts. FCM high-priority quotas and deprioritisation rules were not researched here.

### Gaps
- FCM data-message size limit (commonly 4 KB) and high-priority deprioritisation rules were not fetched this session.
- The UnifiedPush and Web Push payload limit (RFC 8291, about 4 KB) was not confirmed from the fetched page.

## 6. Abuse prevention, server↔gateway auth, and privacy (what gateway/Apple/Google observe; metadata minimisation)

### Takeaway
The gateway must not be an open relay. The Matrix spec's notify endpoint is unauthenticated and not rate-limited, which is the anti-pattern. Combine:
- **Server registration:** per-server identity and keys (Zulip).
- **Bound device handles:** a token is only usable by the server the device enrolled with (Nextcloud signature; Zulip sealed token).
- **Rate limits:** per-server and per-token (Zulip 1000/min; APNs 429).
- **E2EE payloads with opaque key IDs** (Zulip).

Apple and Google will always see the device token, timing, priority/push type, size and the gateway's IP. The origin server's identity can be hidden from them (Nextcloud).

### Cited Findings
- **Matrix:** `/_matrix/push/v1/notify` is "Rate-limited: No; Requires authentication: No". — [Matrix push gateway spec](https://spec.matrix.org/latest/push-gateway-api/)
- **Zulip:**
  - Explicit server registration with ToS.
  - A 1000 req/min per-server limit "to protect the service against DoS".
  - The server never sees device tokens in plaintext (sealed box).
  - The gateway sees token, opaque `push_key_id`, ciphertext and priority.
  - Stated limitation: E2EE "does not protect against a compromised or malicious Zulip server".
  - — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html)
- **Nextcloud:**
  - The device registers its token directly with the proxy, bound to a device identifier and user public key. The proxy verifies each push's signature.
  - The server only knows a SHA-512 hash of the token.
  - Apple and Google "don't even know the Nextcloud server sending the notification".
  - — [Nextcloud push-v2](https://github.com/nextcloud/notifications/blob/master/docs/push-v2.md)
- **ntfy:** the upstream relay sees only a message ID and a SHA-256 of the topic URL. — [ntfy config](https://docs.ntfy.sh/config/); [StackHarbor (secondary)](https://stackharbor.com/en/knowledge-base/ntfy-self-hosted-push-alerts/)
- **Rocket.Chat:** registration + privacy-terms acceptance + monthly quotas per workspace. — [Rocket.Chat push docs](https://docs.rocket.chat/docs/push-notifications)
- **Apple-side signals:** `apns-priority` and `apns-push-type` must accurately reflect the payload. Bursts at priority 5/1 are grouped. Too many consecutive pushes to one token give 429. — [Apple: Sending requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns); [Apple: APNs responses](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns)

### Inferences
- **Recommended auth and abuse controls:**
  - Each self-hosted server generates an Ed25519 identity. It registers with the gateway, optionally proving domain control via a `/.well-known/` file, and signs each request (or uses short-lived JWTs derived from it).
  - The device's push handle is `seal(gateway_pk, {apns_token, platform, server_id_hash, device_nonce})`. The gateway rejects a handle presented by any server other than `server_id_hash`, so a stolen handle is useless to other operators.
  - The gateway can be **stateless for tokens**: it decrypts the handle per request, so there is no token database to leak.
  - Rate limits apply per server (token bucket), per handle (for example 1 push/s with burst coalescing via `apns-collapse-id`) and globally. Servers can be revoked or deny-listed.
- **Metadata minimisation:**
  - Pad ciphertext to a fixed bucket (for example 1 KB or 3 KB) to hide message length.
  - Use opaque key IDs and no room or sender IDs in the clear.
  - Strip the origin server's IP and identity before contacting Apple/Google (Nextcloud property).
  - Do not log handles or ciphertexts; keep only aggregate counters.
- **Dummy pushes** can blur timing, but they cost APNs budget and risk 429s. Only send them as alert-type pushes that the NSE filters, never as VoIP pushes (each must ring). Keep the rate low, for example a random 0–2/day. Batching or delay hurts call latency, so apply it only to low-priority message pushes, never calls.
- **Residual leaks that cannot be removed:**
  - Apple/Google learn device ↔ app ↔ push timing and size.
  - The gateway operator learns which server pushes to which (opaque) device and when, unless pushes are relayed through another hop.

### Gaps
- No published analysis of dummy-push effectiveness against APNs-side traffic analysis was found.
- APNs's exact per-token 429 threshold is undocumented.

## 7. Costs: gateway hosting, Apple fees, push volume fees

### Takeaway
Fixed costs are the Apple Developer Program at 99 USD/yr (2026) and a small VM for the gateway. APNs and FCM have no per-push charges. Commercial alternatives either cap free usage (Zulip 10 users; Rocket.Chat 10k pushes/month) or charge per user (Zulip Basic $3.50/user/month). Self-running a Go gateway is the cheapest option.

### Cited Findings
- Apple Developer Program costs 99 USD/membership year (Enterprise 299 USD); local currency pricing may vary. — [Apple: Enroll](https://developer.apple.com/programs/enroll/); [Apple: Purchase and activation](https://developer.apple.com/support/purchase-activation/)
- FCM is "No-cost". — [Firebase pricing](https://firebase.google.com/pricing)
- **Zulip** self-hosted push: free for up to 10 users and for eligible communities. Basic is $3.50/user/month. Business is $6.67/user/month annual or $8 monthly, with a 25-user minimum. — [Zulip plans](https://zulip.com/plans/)
- **Rocket.Chat:** 10,000 free pushes/month on Community and unlimited on paid plans. — [Rocket.Chat push docs](https://docs.rocket.chat/docs/push-notifications); [FAQ](https://docs.rocket.chat/docs/push-notifications-faq)
- **Mattermost:** HPNS requires a paid Professional/Enterprise plan; TPNS is free but not for production. — [Mattermost HPNS docs](https://docs.mattermost.com/deploy/mobile-hpns.html)

### Inferences
- A Go gateway that holds HTTP/2 connections to APNs and FCM and does small crypto per request is light. A single small VM plus a standby should handle a modest user base. Because payloads are at most 5 KB, bandwidth is negligible.
- The total minimum yearly cost is therefore about $99 plus hosting.

### Gaps
- No VPS price was sourced in this session; hosting cost and throughput per core are estimates and need benchmarking.

## 8. Local Push Connectivity (NEAppPushProvider) restrictions

### Takeaway
Local Push Connectivity is not a general APNs replacement. It works only while the device is on configured Wi-Fi SSIDs and needs a special `app-push-provider` entitlement on request. It suits on-premises or air-gapped deployments, with APNs as the fallback.

### Cited Findings
- It is intended "where an iOS device operates on a local, restricted network that doesn't have access to APNs".
- The extension launches only when the device joins a Wi-Fi network matching a configured SSID, and keeps running while on it.
- It requires the Network Extension entitlement with value `app-push-provider` on both app and extension; the entitlement is requested from Apple.
- Functionality should be limited to local notifications and CallKit alerts.
- The server should use the local connection if it is active and acknowledged, else APNs.
- — [Apple: Local Push Connectivity](https://developer.apple.com/documentation/networkextension/local-push-connectivity)

### Inferences
- It could be an optional feature for enterprise or offline self-hosters (a hospital or ship LAN). The Go server would need a persistent TCP/TLS channel for it. It does not remove the need for the gateway on the open internet.

### Gaps
- Whether recent iOS versions (26.x) extended Local Push Connectivity beyond Wi-Fi SSID matching (for example to private cellular networks) was not verified.
- macOS availability was not verified.

## 9. Recommendation: cheapest reliable, privacy-preserving design (Go)

### Takeaway
Run one small, open-source, project-operated **Go push gateway** that holds the only APNs .p8 key (and later the FCM service account). Self-hosted servers authenticate to it with per-server keys and send it **sealed, server-bound device handles** plus **padded E2EE ciphertext**. The Zulip 2026 model provides the token sealing and opaque key IDs. The Nextcloud model provides hiding the origin server from Apple and Google, plus signature binding. On Android, add UnifiedPush/Web Push so servers can bypass the gateway entirely.

### Cited Findings (basis for the design)
- APNs needs the publisher's .p8 (ES256 JWT, refreshed every 20–60 min). One key covers alert, background and VoIP for the team's bundle IDs. — [Apple token auth](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns); [mattermost-push-proxy README](https://github.com/mattermost/mattermost-push-proxy/blob/master/README.md)
- Zulip's sealed token, per-device key and opaque `push_key_id` model; a per-server rate limit of 1000/min. — [Zulip docs](https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html)
- Nextcloud: per-push signature verified by the proxy; origin hidden from Apple and Google. — [Nextcloud push-v2](https://github.com/nextcloud/notifications/blob/master/docs/push-v2.md)
- Signal/Element X: generic `mutable-content` alert + NSE; Element X uses the filtering entitlement. — [Signal APNSender](https://github.com/signalapp/Signal-Server/blob/main/service/src/main/java/org/whispersystems/textsecuregcm/push/APNSender.java); [element-x-ios NSE](https://github.com/element-hq/element-x-ios/blob/develop/NSE/SupportingFiles/target.yml)
- VoIP pushes must be reported immediately, so only send them for real rings, with `apns-expiration: 0`. — [Apple PushKit](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit)

### Inferences (concrete design)
1. **Registration (device):**
   - The app gets its APNs token, and its PushKit VoIP token on iOS.
   - It fetches the gateway's X25519 public key (pinned in the app).
   - It builds `handle = sealed_box(gw_pk, {token, kind, server_binding=H(server_pubkey), created})`.
   - It generates a per-device symmetric key `K_dev` with a 1-byte algorithm prefix and an opaque `key_id`.
   - It sends `{handle, voip_handle?, K_dev, key_id, capabilities: {voip, callkit_allowed(region), platform}}` to its own homeserver. The homeserver never sees the APNs token.
2. **Server ↔ gateway auth:**
   - Each server has an Ed25519 keypair. Registration is automatic (optionally proven via `https://<domain>/.well-known/...`), and requests are signed (HTTP Message Signatures or a short JWT).
   - The gateway checks `H(server_pubkey) == server_binding` inside the handle.
   - Per-server token bucket and per-handle limits apply; revocation is by server key.
   - Return 410-equivalent errors to servers so they prune dead handles (mirroring APNs `Unregistered`).
3. **Message push:**
   - `apns-push-type: alert`, priority 10 for user-visible messages or 5 for low-importance ones.
   - `{aps:{alert:{loc-key:"NEW_MSG"}, mutable-content:1}, k:key_id, c:base64(pad(ciphertext, 1–3 KB bucket))}`. Optionally add `apns-collapse-id` = a random per-conversation alias.
   - The NSE decrypts with `K_dev` from the app-group keychain. Over-size messages use ping-and-fetch.
   - With the filtering entitlement, the NSE drops dummy pushes, read-elsewhere events and duplicates.
4. **Call push (iOS with CallKit allowed):**
   - `apns-push-type: voip`, topic `<bundle>.voip`, `apns-expiration: 0`, payload `{call_id, c: encrypted caller/session hint}` of at most 5120 bytes.
   - The app reports a placeholder call to CallKit synchronously, then decrypts and updates it.
   - Cancel goes over the data channel, or as an NSE-filtered alert.
   - **China / CallKit-disabled / native macOS:** a time-sensitive alert push plus NSE plus local ringing notification.
5. **Android (later):**
   - The gateway adds FCM high-priority **data** messages with the same envelope (FCM is free).
   - Servers can also accept a UnifiedPush/Web Push endpoint per device and push directly, RFC 8291-encrypted, with no gateway involvement.
6. **Gateway implementation:**
   - Write it in Go (same language as the server), about 1–2k LOC. Components:
     - HTTP/2 APNs client with JWT refresh every ~40 min;
     - per-token coalescing;
     - 429/410 handling;
     - FCM v1 later;
     - Prometheus counters only, with no content or handle logging.
   - Use mattermost-push-proxy (Go, Apache-2.0) as a fork or reference for APNs/VoIP specifics.
   - Avoid adopting Sygnal (Python, AGPL, unauthenticated Matrix API).
   - Publish the gateway source so operators can audit what it sees.
7. **Cost:** $99/yr Apple + one small VM (+ standby). No per-push fees.
8. **Optional:** Local Push Connectivity for LAN-only enterprise deployments (needs entitlement), falling back to the gateway.

### Gaps
- No benchmark of Go APNs libraries (for example sideshow/apns2, licence and maintenance status) was done.
- No verified 2026 FCM/HTTP v1 quota details.
- The status of the China CallKit rule and the exact iOS 26.4 `mustReport` semantics should be confirmed before finalising the call-push design.
