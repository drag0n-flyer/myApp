# Connectivity, Reliability and Media-Server Choices for a Self-Hosted Go + iOS/macOS (libwebrtc) Calling Platform (as of Oct 2026)

Scope: STUN/TURN choice, how often a relay is needed, mobile network changes, SFU choice for later group calls, LiveKit vs raw libwebrtc, and iOS signaling. The targets are reliability, low running cost, single-node Docker Compose self-hosting, and a good fit with Go.
Research date: 2026-10-27. Pages fetched today are marked. Older data is flagged.

---

## 1. How often calls need a TURN relay; effect of CGNAT/IPv6; TURN over UDP vs TCP vs TLS/443

### Takeaway
Plan for about 15–25% of 1:1 peer-to-peer calls to need a relay. Consumer traffic tends lower and enterprise or locked-down networks higher (anywhere from 0% to 50% has been reported). Nearly all hard numbers are about 10 years old and come from vendors. No recent figures from Google, Twilio, Jitsi or Signal were found, so the project should measure its own rate. Of the relayed calls, most go over TURN/UDP, a meaningful minority need TURN/TCP, and well under 1% need TURN/TLS. That small TLS-on-443 slice is the difference between "works everywhere" and "fails on corporate or hotel Wi-Fi".

### Cited Findings
- Chrome-era P2P data (Philipp Hancke, "fippo"): 12.1% of calls on TURN/UDP, 5% on TURN/TCP and just under 0.5% on TURN/TLS, about 17.7% relayed in total. Only about 4% of SFU calls used TURN. (The page returned 403 to the fetcher, so these figures come from the search-result extract.) — [fippo, "What kind of TURN server is being used?"](https://medium.com/@fippo/what-kind-of-turn-server-is-being-used-d67dbfc2ff5d)
- callstats.io data (2015–16, published April 2016): average session-setup failure rate of 12%, with 85% of failures caused by NAT/firewall traversal. 22% of conferences needed some kind of TURN relay and about 9% needed TCP. Not every customer had TURN/TCP or TURN/TLS deployed, so the failure rate is partly caused by missing relays. **Outdated (about 10 years old).** — [webrtcHacks, "The Big Churn" (Lumiaho & Singh)](https://webrtchacks.com/usage-stats/)
- Tsahi Levent-Levi (Nov 2025): the common lore is that about 80% of connections resolve with host or STUN candidates. He has seen 0–50% relayed depending on user base, and calls 30% "a tad high" for most of the industry. — [BlogGeek.me, "WebRTC TURN Servers: When you NEED it"](https://bloggeek.me/webrtc-turn/)
- Transport guidance from the same source: for P2P, try direct, then TURN/UDP, then TURN/TCP, then TURN/TLS. For SFUs, TURN/TCP and TURN/TLS matter most because the SFU is publicly reachable. UDP on 443 is often left open when other ports are blocked. Run TURN on 443 to get through restrictive networks. ICE-TCP on media servers reduces the need for TURN/TCP but not for TURN/TLS. — [BlogGeek.me](https://bloggeek.me/webrtc-turn/)
- LiveKit's docs say TURN/TLS "looks no different from regular HTTPS traffic" to firewalls and gives "the broadest coverage… including those behind corporate firewalls". Without a load balancer, `turn.tls_port` must be 443 because that port is advertised to clients. — [LiveKit self-hosting deployment docs (fetched 2026-10-27)](https://docs.livekit.io/home/self-hosting/deployment/)
- Cloudflare's managed TURN exposes UDP 3478 (alt 443/udp), TCP 3478 (alt 80/tcp) and TLS 5349 (alt 443/tcp). This shows that serious providers treat 443 for both UDP and TLS as standard. — [Cloudflare Realtime TURN docs (updated Sep 25, 2026)](https://developers.cloudflare.com/realtime/turn/)
- Signal sets up a P2P connection only when you are the caller or the caller is in your contacts. Calls from non-contacts are relayed through Signal's servers to hide your IP. An optional "Always relay calls" setting relays every call, which "may slightly reduce call quality". — [Signal blog, "Video calls for Signal out of beta"](https://signal.org/blog/signal-video-calls/); [Privacy Guides discussion](https://discuss.privacyguides.net/t/mention-that-signal-and-session-leaks-ip-in-calls-p2p/17828)
- Mobile IPv6/CGNAT: per NANOG posts, T-Mobile US and AT&T carry about 84–90%+ of traffic over IPv6, and "IPv6 only with IPv4 CGNAT" describes most large US mobile networks. Reliance Jio is above 92% IPv6. These are secondary or mailing-list figures and they conflict (T-Mobile is quoted at 84–93% across sources). — [NANOG Nov 2025](https://seclists.org/nanog/2025/Nov/5); [NANOG Jun 2025](https://seclists.org/nanog/2025/Jun/46); [voxbooster IPv6 stats 2026 (secondary, cites APNIC)](https://voxbooster.com/blog/ipv6-adoption-statistics-2026)
- A vendor guide claims 85% of enterprise WebRTC connections need TURN. This is marketing material, conflicts with every other source, and should be treated with suspicion. — [celloip.com production guide 2026](https://celloip.com/blog/webrtc-turn-server-production-guide/)

### Inferences
- IPv4 CGNAT on cellular often has address- and port-dependent ("symmetric") mapping, which defeats STUN hole punching when both peers are behind it. Dual-stack phones on IPv6-heavy carriers (T-Mobile, Jio, AT&T) can often connect directly over IPv6, because carriers usually run stateful IPv6 firewalls rather than NAT, and simultaneous-open UDP generally gets through those. **Set up the TURN server dual-stack and make sure clients gather IPv6 candidates.** That gives ICE an IPv6 direct path and IPv6 relay candidates. The direct-success rate for phone-to-phone calls on two IPv6 carriers is probably higher than the old ~80% figure, but I found no measurement to prove this.
- Phone-to-phone calls where one side is on IPv4-only Wi-Fi or a corporate network and the other on IPv6-only cellular can't connect directly across address families (NAT64 doesn't help ICE without TURN). Those calls need a dual-stack TURN server to bridge v4 and v6. This is a strong argument for running your own dual-stack relay.
- A privacy-first product modelled on Signal (relay for non-contacts, optional "always relay") pushes the relayed share far above 20%. Budget for relay bandwidth as if a majority of calls might be relayed. This is affordable for audio (see section 3).
- Minimum listener set for "works everywhere": UDP 3478, UDP 443 if a free IP or port is available, TCP 3478 and TLS 443. Put TLS on a dedicated IP, or share 443 with HTTPS through an SNI/ALPN-multiplexing L4 proxy.

### Gaps
- I found no recent (2023–2026) published relay-rate figures from Google, Twilio, Jitsi or Signal, and no academic measurement study. Google's internal WebRTC stats aren't public.
- No source measures direct-connectivity rates specifically for IPv6 cellular-to-cellular calls.
- Primary APNIC or World IPv6 Launch per-carrier data was not retrieved. Carrier IPv6 shares above come from secondary sources.

---

## 2. TURN server options and ephemeral credentials

### Takeaway
**coturn** is still the default battle-tested choice: actively maintained again, 4.18.0 released Sep 2026, BSD licence, supports UDP/TCP/TLS/DTLS and the TURN REST shared-secret scheme. **Pion TURN** (MIT, Go) is a solid library you can embed in a Go server, but it is a toolkit: you write the auth, quotas, peer-IP filtering and listeners yourself. **eturnal** (Apache-2.0, Erlang) is a simpler, safe-by-default alternative to coturn. **LiveKit's built-in TURN** is convenient if you adopt LiveKit. **Cloudflare TURN** ($0.05/GB, 1 TB/month free, anycast, 443 for UDP and TLS) is by far the cheapest managed fallback. **Twilio** costs 8–16 times more.

### Cited Findings
**coturn**
- 14.5k stars and 2.3k forks. Client-to-server transports: UDP, TCP, TLS (including 1.3), DTLS 1.0/1.2, and experimental SCTP. Relays UDP and TCP. TLS/DTLS need OpenSSL 3.0 or newer. RFC 8489 `MESSAGE-INTEGRITY-SHA256`/`USERHASH` and RFC 8656 ICMP relaying are **not** implemented. Has an OSS-Fuzz badge. Supports the "TURN REST API" (draft-uberti-behave-turn-rest-00) for time-limited, secret-based credentials. — [coturn GitHub (fetched 2026-10-27)](https://github.com/coturn/coturn)
- Release activity: Docker image `4.18.0-r0` published **2026-09-08** (changelog dated 2026-09-08). Recent releases are hardening work: **DTLS listeners are now opt-in** (`--dtls`); **stateless nonce is on by default**, so unauthenticated UDP requests are answered without allocating session state (an anti-DoS change); `--drop-invalid-packets`, `--no-dtls` and `--no-cli` were removed; over-long USERNAME/REALM/NONCE is rejected; attributes after MESSAGE-INTEGRITY are ignored; `--external-ip` is applied per address family; 4.17.2 fixed outgoing UDP TTL being pinned to 1. — [coturn releases (fetched 2026-10-27)](https://github.com/coturn/coturn/releases)
- Licence: the repo links a LICENSE file, which the fetcher didn't render. From prior knowledge it is the 3-clause BSD licence. — [coturn GitHub](https://github.com/coturn/coturn)
- Security history (from prior knowledge, not re-fetched today, so verify): CVE-2020-26262, where a loopback/0.0.0.0 peer bypass let attackers reach services on the TURN host. Mitigate with `denied-peer-ip`/`no-loopback-peers` and by blocking private ranges. Also CVE-2020-6061/6062 (Talos: heap overflow and DoS in older versions). — [NVD CVE-2020-26262](https://nvd.nist.gov/vuln/detail/CVE-2020-26262); [NVD CVE-2020-6061](https://nvd.nist.gov/vuln/detail/CVE-2020-6061)

**Pion TURN (Go)**
- MIT licence, about 2.3k stars, module path `/v5`. Implements RFC 5389 (STUN), RFC 5766 (TURN), RFC 6062 (TCP allocations) and RFC 6156 (IPv6). Auth is through a custom `AuthHandler` (an `lt_cred.go` helper exists) and there is a mutual-TLS example. It describes itself as an API for building TURN servers and clients, not a configured binary. Commercial support is available from team@pion.ly. The README makes no explicit "production-ready" claim. — [pion/turn GitHub (fetched 2026-10-27)](https://github.com/pion/turn)

**eturnal**
- Apache-2.0, written in Erlang (rebar), version 1.12.3 referenced. Uses the "REST API for Access to TURN Services" credential mechanism. Ships a Docker image, Kubernetes docs and IPv6 relay support (`relay_ipv6_addr`). — [eturnal GitHub (fetched 2026-10-27)](https://github.com/processone/eturnal)

**LiveKit embedded TURN**
- LiveKit server has a built-in TURN with TURN/TLS (`turn.enabled`, `tls_port`, `domain`, cert/key). LiveKit terminates TLS itself, and `tls_port` must be 443 when there is no L4 load balancer. — [LiveKit deployment docs](https://docs.livekit.io/home/self-hosting/deployment/)

**Managed TURN pricing (fetched 2026-10-27)**
- **Cloudflare Realtime TURN/SFU:** **$0.05 per GB of egress**. The **first 1,000 GB/month is free**, shared between SFU and TURN. Traffic between TURN and the Cloudflare SFU isn't double-charged. Page last updated Sep 22, 2026. — [Cloudflare Realtime pricing](https://developers.cloudflare.com/realtime/pricing/)
- Cloudflare TURN runs on anycast: about 95% of the internet-connected population is within about 50 ms of a Cloudflare location. — [Cloudflare blog, TURN and anycast](https://blog.cloudflare.com/webrtc-turn-using-anycast/)
- **Twilio Network Traversal Service:** STUN is free. TURN costs **$0.40/GB** (US West, Germany), **$0.60/GB** (Singapore, Mumbai, Tokyo) and **$0.80/GB** (Sydney, São Paulo). The page also says usage is "billed in megabyte". — [Twilio STUN/TURN pricing](https://www.twilio.com/en-us/stun-turn/pricing)
- Xirsys: pricing not retrieved (see Gaps).

### Inferences
- **Ephemeral credentials best practice** (TURN REST scheme, supported by coturn and eturnal and easy to build on Pion's `AuthHandler`):
  - The Go API issues `username = "<unix_expiry>:<opaque_user_id>"` and `credential = base64(HMAC-SHA1(shared_secret, username))`.
  - Set a TTL of minutes to a few hours. It must cover the longest expected call, because TURN allocation refreshes reuse the credential.
  - Hand the credentials out only to authenticated clients, right before each call.
  - Keep the shared secret only in the Go server and the TURN config, and rotate it by supporting two secrets during the changeover (coturn accepts multiple `static-auth-secret` entries).
  - Never ship static TURN credentials in the app binary.
- **Hardening any self-hosted TURN:**
  - Deny relaying to loopback, RFC 1918, link-local, CGNAT 100.64/10, the cloud metadata IP 169.254.169.254, and your own server's private network. This is the CVE-2020-26262 class of issue.
  - Cap allocations per user and bandwidth per allocation.
  - Run with `network_mode: host`, or a narrow relay port range, in Docker. Publishing tens of thousands of UDP ports through docker-proxy is slow.
- **Go fit:** embedding Pion TURN in the Go signaling binary gives one deployable and shared auth (it can validate a session token directly), and it's what LiveKit uses internally. The cost is that you own the operational hardening coturn already has: stateless nonce, peer-IP deny lists, quotas, Prometheus metrics. For a small team, coturn as a separate container with REST secrets is less risky. Pion TURN makes sense if you want custom policy, such as per-user relay quotas tied to your DB.
- **Belt and braces:** list your own TURN first and Cloudflare TURN second in `iceServers`. Cloudflare's anycast on 443 covers regions where your single node is far away, and at audio bitrates the 1 TB free tier would rarely be exceeded.

### Gaps
- The pages that loaded don't show coturn's recent CVEs (2023–2026). The NVD/GitHub security advisories should be checked directly.
- Pion TURN's latest exact version and release date weren't captured. I found no published list of large production deployments beyond pion/webrtc and pion/ice. That LiveKit's built-in TURN uses Pion TURN is from prior knowledge of LiveKit's go.mod, not verified today.
- Xirsys pricing was not retrieved.
- I didn't confirm whether Twilio bills TURN ingress plus egress or egress only.

---

## 3. Relay bandwidth cost per GB and per relayed audio-call hour

### Takeaway
Relayed audio is almost free on flat-rate European hosts and still trivial on Cloudflare. A relayed 1:1 audio call costs about 30–40 MB of TURN egress per hour, depending on ptime and overhead. That is about **$0.002/h on Cloudflare** (zero inside the 1 TB free tier), **about $0.003/h on AWS**, **about $0.015/h on Twilio**, and effectively **$0 on Hetzner/OVH/DigitalOcean included transfer**. The cheapest practical relay is a small Hetzner (or OVH) VM, with Cloudflare TURN as an optional fallback.

### Cited Findings
- **Hetzner dedicated (root) servers:** 1 Gbit uplink with **unlimited traffic**. 10 Gbit uplinks include 20 TB/month, then **€1 ($1.20) per TB**. — [Hetzner Docs, Traffic (last changed 2024-05-21)](https://docs.hetzner.com/robot/general/traffic/)
- **Hetzner Cloud:** overage billed in 100 MB blocks. The per-location included traffic and per-TB rate weren't on the FAQ page. — [Hetzner Cloud billing FAQ](https://docs.hetzner.com/cloud/billing/faq/)
- **DigitalOcean:** every Droplet includes outbound transfer, for example CPU-Optimized 2 vCPU/4 GiB at $42/mo with 4,000 GiB and 4 vCPU at $84/mo with 5,000 GiB. Overage is $0.01/GiB (overage rate from prior knowledge of the same page, not explicitly captured). — [DigitalOcean Droplet pricing (fetched 2026-10-27)](https://www.digitalocean.com/pricing/droplets)
- **AWS EC2 internet egress:** 100 GB/month free, then about **$0.09/GB** for the first 10 TB in US/EU (Singapore starts at about $0.12/GB). These are secondary sources; check AWS's own page. — [beSharp, AWS data transfer costs 2026](https://blog.besharp.it/aws-data-transfer-costs-in-a-nutshell/); [egresscost.com (verified 2026-06)](https://egresscost.com/aws/data-transfer-pricing)
- **Cloudflare TURN:** $0.05/GB egress, 1,000 GB/month free. **Twilio TURN:** $0.40–$0.80/GB. — [Cloudflare pricing](https://developers.cloudflare.com/realtime/pricing/); [Twilio pricing](https://www.twilio.com/en-us/stun-turn/pricing)

### Inferences (worked numbers)
- **Assumptions:** Opus at 20–32 kbps payload. Wire overhead per packet is IPv4 20 B + UDP 8 B + RTP 12 B + SRTP auth tag 10 B + TURN ChannelData 4 B, about 54 B. At 20 ms ptime (50 pps) that adds about 21.6 kbps; at 60 ms ptime (about 16.7 pps) only about 7.2 kbps. **This overhead matters a lot for a low-data app.** Use ptime 40–60 plus DTX.
- **Per relayed 1:1 call-hour**, TURN egress is two streams (one to each peer). At about 40 kbps on the wire: 2 × 40 kbps × 3600 s = 288 Mbit, about **36 MB/h**. At about 25 kbps on the wire (60 ms ptime, 18 kbps Opus): about **22 MB/h**. Ingress roughly equals egress but is free almost everywhere.
- **Cost per relayed call-hour at 36 MB:**
  - Twilio US/EU: about $0.0144
  - AWS: about $0.0032
  - Cloudflare: about $0.0018 (the free tier covers about 27,000 relayed call-hours a month)
  - DigitalOcean overage: about $0.00035
  - Hetzner 10G overage: about €0.00004
  - Hetzner 1G or DO included transfer: $0
- **Watch for double relaying:** if both clients are forced to relay-only, each through *its own* TURN allocation, media traverses two allocations. On one TURN server that's 2 relay hops and twice the egress. On two servers it adds latency too. With one server, per-call egress about doubles, which is still trivial for audio.
- **Video changes the picture:** 500 kbps video is about 450 MB/h relayed. That is $0.18/h on Twilio but still about $0 on Hetzner. Choose flat-rate hosting if video is ever relayed.
- **Cheapest practical setup:** a Hetzner CX/CPX/CAX cloud VM (a few €/month) or an OVH VPS (OVH markets "unlimited" public traffic on VPS, from prior knowledge, not verified today) running coturn or Pion TURN, plus Cloudflare TURN as a geographic fallback.

### Gaps
- Hetzner Cloud included-traffic amounts per region (EU vs US vs Singapore) and the cloud overage price were not confirmed today. From memory, EU includes about 20 TB and US/SG much less, at about €1/TB EU overage, but **verify**.
- OVH traffic policy and AWS's official current page were not fetched directly.

---

## 4. Reliability: ICE restart, continual gathering, Wi-Fi to cellular handover on iOS, consent freshness, relay-only policy, reconnect UX, TCP fallback

### Takeaway
Use libwebrtc's continual gathering plus its built-in network monitor. That handles many Wi-Fi to cellular switches without signaling. Always back it with an app-driven **ICE restart** over signaling when the ICE state goes `disconnected` for more than about 2–3 s or `failed`, and design the signaling channel to survive the same network change. Consent freshness (RFC 7675) means a path that stops answering is declared dead within about 30 s. Relay-only (`iceTransportPolicy = relay`) gives IP privacy at the cost of a bit more latency and server bandwidth. TURN/TCP and TLS work as fallbacks but cause head-of-line blocking and worse audio under packet loss.

### Cited Findings
- **Consent freshness:** RFC 7675 requires WebRTC endpoints to send periodic STUN binding requests on the selected pair. Consent expires (and media must stop) if no response arrives within 30 s; checks are sent about every 5 s, randomized. These figures are from prior knowledge of the RFC; the fetched text indexed poorly. — [RFC 7675](https://www.rfc-editor.org/rfc/rfc7675.txt)
- **ICE restart** is the standard recovery when the selected path breaks. A discuss-webrtc developer says continual gathering "decreases need for ice restart, but does not eliminate it", and recommends treating restart as a catch-all. The same thread reports an iOS Wi-Fi to 4G switch recovering *without* ICE restart (state went Disconnected, then Failed, then media resumed). — [discuss-webrtc: "When is ICE restart needed on iOS/Android?"](https://groups.google.com/g/discuss-webrtc/c/3jxTy9v8mD4)
- Continual gathering lets an agent pick up new interfaces and IP changes, enabling Wi-Fi to mobile handover "without the need for an ICE restart". This is from the rawrtc project (about 9 years old), so it states intent, not libwebrtc behavior. — [rawrtc issue #75](https://github.com/rawrtc/rawrtc/issues/75)
- On iOS there is no `navigator.connection` change event (that is a browser API). Native apps must trigger on ICE state, stats stalls, or OS path-change notifications. Third-party write-up. — [RTMA, Wi-Fi to cellular handover](https://www.real-time-media-architecture.com/webrtc-protocol-stack-signaling-servers/connection-recovery-ice-restart/handling-wifi-to-cellular-network-handover/)
- libwebrtc's iOS SDK has a native network monitor, `sdk/objc/components/network/RTCNetworkMonitor.mm`. From prior knowledge it is built on Apple's Network framework `nw_path_monitor` and feeds interface-change events into the ICE port allocator. — [webrtc.googlesource.com RTCNetworkMonitor.mm](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/objc/components/network/RTCNetworkMonitor.mm)
- Signal's privacy model relays calls from non-contacts, and "Always relay calls" relays everything, which shows relay-only works for a mass-market iOS app. — [Signal blog](https://signal.org/blog/signal-video-calls/)
- callstats.io: about 9% of conferences needed TCP, "which may not be the best transport for media quality but is better than failing". — [webrtcHacks](https://webrtchacks.com/usage-stats/)

### Inferences (recommended client logic for native iOS/macOS libwebrtc)
- `RTCConfiguration` settings:
  - `continualGatheringPolicy = .gatherContinually`
  - `sdpSemantics = .unifiedPlan`
  - `bundlePolicy = .maxBundle`, `rtcpMuxPolicy = .require`
  - `tcpCandidatePolicy` enabled
  - leave `candidateNetworkPolicy = .all` so cellular candidates are gathered while on Wi-Fi
  - `iceCandidatePoolSize` of 1–2 to pre-gather relay candidates for faster setup
  - consider shortening `iceConnectionReceivingTimeout` / `iceBackupCandidatePairPingInterval` to detect dead paths faster at a small data cost
- These property names are from the libwebrtc ObjC headers (`RTCConfiguration.h`), not re-verified today. Confirm against the M-branch you build.
- **Handover state machine:**
  1. On iceConnectionState `disconnected`, show "Reconnecting…" after about 1 s and start a 2–3 s timer.
  2. If not recovered, the caller (or a designated side, to avoid glare) does `restartIce()`, sends a new offer over signaling and gets an answer.
  3. On `failed`, restart immediately.
  4. Give up after about 20–30 s, in line with the consent window, and offer "Call again".
  5. Use `NWPathMonitor` in the app to *proactively* trigger an ICE restart when the primary interface changes and the current pair is on the old interface.
  6. Make the signaling WebSocket reconnect first (exponential backoff, resume token) so restart offers can flow.
- **Relay-only for privacy:** set `iceTransportPolicy = .relay` for calls with unverified contacts (Signal model) or as a user toggle. Because both legs then go through your TURN server, costs follow section 3. Latency stays acceptable if the relay is geographically central for your users.
- **TURN/TCP/TLS costs:** TCP retransmissions add delay under loss, and audio stalls instead of degrading. TLS adds a handshake (about 1–2 RTT) at setup and per-packet framing overhead. Keep UDP first in priority, so libwebrtc's ICE prioritization chooses TLS only when UDP fails.
- **UX patterns** (as in WhatsApp, FaceTime and Signal):
  - Keep the call UI and CallKit call alive while reconnecting.
  - Show a "Reconnecting…" banner and mute-tone. CallKit lets you keep the call "connected" while media is down.
  - Report a "poor connection" indicator from `getStats` (RTT, loss, jitter).
  - Don't drop the CallKit call until the give-up timer fires.

### Gaps
- I found no authoritative libwebrtc documentation on exactly how the iOS build re-gathers on `nw_path_monitor` events or on known iOS-specific handover bugs. The libwebrtc bug tracker and `p2p/base/` source would be the next sources.
- No published measurements of handover recovery time (with and without continual gathering) on iOS were found.

---

## 5. SFU choices for group calls

### Takeaway
**LiveKit** (Go, Apache-2.0, built on Pion) is the clear fit. It is mature, actively developed, has a first-party Swift SDK for iOS/macOS with E2EE, includes built-in TURN/TLS, and deploys as a single node in Docker. mediasoup (ISC, Node/C++) is excellent but is a library you'd glue to Node, not Go. Janus (GPL-3.0, C) brings licence and integration friction. Jitsi Videobridge (Apache-2.0, Java) is heavy and tied to the Jitsi/XMPP stack. Galene (MIT, Go) is small and elegant but browser-centric, with no native iOS SDK. ion-sfu (MIT, Go) is effectively dormant.

### Cited Findings
- **LiveKit server:** Apache-2.0, Go. Self-hosting docs cover built-in TURN/TLS and single- or multi-node deployment. — [livekit/livekit GitHub](https://github.com/livekit/livekit); [LiveKit deployment docs](https://docs.livekit.io/home/self-hosting/deployment/)
- **LiveKit Swift SDK:** official iOS/macOS/tvOS/visionOS SDK with v2 breaking changes and SwiftUI example apps. — [livekit/client-sdk-swift](https://github.com/livekit/client-sdk-swift)
- **LiveKit E2EE:**
  - Covers media tracks and data channels, enabled at room level, with each participant opting in through the SDK.
  - Key provider can be a shared room key or per-participant keys (custom provider for rotation). LiveKit "does not (and cannot) store or transport encryption keys", so the app must distribute them.
  - Signaling messages are **not** E2E encrypted (TLS only).
  - The `e2ee` option is deprecated in favour of `encryption`.
  - Sources: [LiveKit encryption docs](https://docs.livekit.io/transport/encryption/)
  - The Swift SDK uses frame encryption with AES-GCM (`EncryptionOptions`, `encryptionType: .gcm`, `BaseKeyProvider`) and supports iOS 13+ and macOS 10.15+. This comes from a third-party mirror of the SDK docs; check the official API reference. — [mintlify mirror of Swift SDK encryption guide](https://www.mintlify.com/livekit/client-sdk-swift/guides/encryption)
- **mediasoup:** ISC licence (Node.js API with a C++ worker). — [versatica/mediasoup](https://github.com/versatica/mediasoup)
- **Janus:** GPL-3.0, C. — [meetecho/janus-gateway](https://github.com/meetecho/janus-gateway)
- **Jitsi Videobridge:** Java/Kotlin SFU "up to hundreds of conferences per server". Apache-2.0 per prior knowledge (the licence field wasn't captured in the fetch). — [jitsi/jitsi-videobridge](https://github.com/jitsi/jitsi-videobridge)
- **Galene:** MIT, Go. — [jech/galene](https://github.com/jech/galene)
- **ion-sfu:** "Pure Go WebRTC SFU", MIT. No archive notice, but no releases were visible on the fetched page. It still has 32 open issues and 18 open PRs. — [ionorg/ion-sfu](https://github.com/ionorg/ion-sfu)

### Comparison (synthesis; licence facts cited above, the rest is inference or prior knowledge)
| SFU | Lang / licence | Go integration | Native iOS SDK | E2EE | Self-host ease | Notes |
|---|---|---|---|---|---|---|
| LiveKit | Go / Apache-2.0 | Excellent: Go server SDK, can embed or extend | Official Swift SDK | Yes (AES-GCM frame encryption, app-managed keys) | Single binary + Redis optional; Docker; built-in TURN | Most active Pion-based SFU; simulcast/SVC, DTX/RED options |
| mediasoup | C++ worker + Node / ISC | Only via a Node sidecar | Community (libmediasoupclient C++) | Possible via insertable streams; DIY | Moderate; you write the app server | Very efficient, but not Go |
| Janus | C / GPL-3.0 | HTTP/WS API from Go | None official (use raw libwebrtc) | DIY | Moderate | GPL is a concern only if you distribute modified binaries |
| Jitsi Videobridge | Java / Apache-2.0 | Poor (XMPP/Colibri, Jicofo) | Jitsi Meet SDK (whole app, React Native) | Jitsi E2EE (insertable streams) | Heavy (Prosody + Jicofo + JVB) | Best if you want all of Jitsi |
| Galene | Go / MIT | Good (Go) but standalone server | None | Not built-in (from prior knowledge) | Very easy | Aimed at teaching/meetings, browser client |
| ion-sfu | Go / MIT | Library | None | No | n/a | Dormant; don't start new work on it |

### Inferences
- Audio-only group calls are cheap on an SFU: N participants means N×(N−1) downstream audio streams. With DTX and LiveKit's "active speaker only" or audio-level-based forwarding, actual egress is much lower. A 4-person call at about 30 kbps is about 360 kbps of server egress, about 160 MB/h. That is still free on Hetzner's included traffic.
- One SFU node on a 4–8 vCPU Hetzner box will handle hundreds of concurrent audio participants (estimate; LiveKit publishes its own benchmarks, not fetched here).

### Gaps
- Today's resource-use benchmarks (CPU per participant) for LiveKit vs mediasoup vs JVB were not fetched.
- The exact last release date of ion-sfu wasn't captured (from prior knowledge it was v1.11.0 around 2021). Galene's current E2EE status was not verified.

---

## 6. Build on LiveKit (server + Swift SDK) vs raw libwebrtc + custom signaling?

### Takeaway
**Recommended: a hybrid that converges on LiveKit.** Run LiveKit (with its built-in TURN/TLS on 443) for group calls from day one and seriously consider it for 1:1 too. It removes the hardest work for a small team: reconnection/resume logic, ICE restart orchestration, TURN/TLS, simulcast and E2EE plumbing. It also gives IP privacy by default, since peers only see the SFU's IP. Keep raw libwebrtc + custom Go signaling + coturn/Pion TURN for 1:1 *only* if measurements show that P2P's lower latency and zero server bandwidth justify maintaining two media paths.

### Cited Findings
- LiveKit is Apache-2.0 Go, with an official Swift SDK, room-level E2EE with app-controlled keys, and built-in TURN/TLS on 443. — [livekit/livekit](https://github.com/livekit/livekit); [client-sdk-swift](https://github.com/livekit/client-sdk-swift); [encryption docs](https://docs.livekit.io/transport/encryption/); [deployment docs](https://docs.livekit.io/home/self-hosting/deployment/)
- Only about 4% of SFU calls needed TURN vs about 17.7% of P2P calls, because the SFU has a public address and supports ICE-TCP. — [fippo (via search extract)](https://medium.com/@fippo/what-kind-of-turn-server-is-being-used-d67dbfc2ff5d)

### Inferences
- **Pros of LiveKit for everything:**
  - one media path to debug
  - SFU-terminated media hides IPs between users, matching the relay-for-privacy goal without TURN
  - the SFU is publicly reachable, so connection success is higher (see the 4% vs 17.7% figures)
  - Swift SDK handles reconnect/resume and network changes (LiveKit implements quick "resume" and full-reconnect paths, from prior knowledge)
  - E2EE is built in
  - Go server SDK and webhooks fit the Go backend
  - one Docker Compose service plus optional Redis
- **Cons:**
  - Every 1:1 call costs server bandwidth and adds a hop. For audio on flat-rate hosting this costs almost nothing, but latency goes up if the SFU is far from both users.
  - Low-level control is reduced. Opus ptime, maximum bitrate, FEC/RED and DTX are exposed through `AudioPublishOptions`/`RoomOptions`, but deep SDP munging (for example forcing 60 ms ptime or custom codec params) is harder than with raw libwebrtc.
  - The Swift SDK uses LiveKit's own libwebrtc build (LiveKit WebRTC xcframework, from prior knowledge), so you depend on their WebRTC cadence for security patches.
  - The E2EE key distribution still has to be designed by you, for example via your Go server or an MLS/Signal-protocol channel.
  - Signaling metadata is visible to the server.
- **Pros of raw libwebrtc + custom Go signaling:**
  - maximum control over the low-data audio profile (ptime 60, Opus 6–16 kbps, DTX, no RTCP extras)
  - true P2P with no server media cost when a direct path exists
  - you can apply the Signal-style contact-based relay policy
- **Cons:**
  - you must build and own ICE restart and glare handling, reconnection, CallKit integration, TURN ops, and E2EE (SFrame via `RTCFrameEncryptor`/`FrameCryptor`) yourself
  - group calls later still need an SFU, so you end up with two stacks anyway
- **Low-data tuning on LiveKit:**
  - Disable video by default.
  - Set Opus `maxBitrate` to about 16–24 kbps and enable DTX.
  - Consider disabling RED (it roughly doubles audio bitrate) except on lossy links.
  - Use `adaptiveStream`/`dynacast` for any video.
  - Whether ptime above 20 ms can be configured in the LiveKit Swift SDK is unverified (gap).

### Gaps
- No fetched source confirms which LiveKit Swift SDK audio options exist (ptime, DTX, RED toggles) as of late 2026.
- I found no published comparison of LiveKit-mediated 1:1 latency vs P2P.

---

## 7. Signaling on iOS: WebSocket background limits, CallKit and PushKit

### Takeaway
On iOS you cannot keep a signaling WebSocket alive in the background to receive calls. Incoming calls must arrive as **PushKit VoIP pushes** (via APNs from the Go server). The app must report each one to **CallKit** immediately, then open the WebSocket and start ICE. During an active CallKit call with an active audio session, the app keeps running, so the WebSocket can stay open for ICE restarts and renegotiation.

### Cited Findings
- Apple's PushKit documentation: "Responding to VoIP Notifications from PushKit". The page body didn't render in the fetch. From prior knowledge of Apple's docs and the iOS 13 SDK rule: apps **must** call `CXProvider.reportNewIncomingCall` for every VoIP push before the push handler completion, or iOS terminates the app and may stop delivering VoIP pushes. — [Apple: Responding to VoIP Notifications from PushKit](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit)
- LiveKit's signaling (control messages) is TLS-encrypted but not E2E encrypted. This is relevant if the app layers its own E2EE key exchange over signaling. — [LiveKit encryption docs](https://docs.livekit.io/transport/encryption/)

### Inferences
- **Flow:**
  1. The caller's app uses the WebSocket or HTTPS to tell the Go server.
  2. The Go server sends an APNs VoIP push (`apns-push-type: voip`, topic `<bundle>.voip`) carrying a call ID, a minimal encrypted payload and a short-lived token.
  3. The callee's app reports to CallKit at once, then connects the WebSocket in parallel and fetches the offer (or LiveKit token) plus TURN credentials.
  4. On answer, `CXAnswerCallAction` triggers SDP answer/ICE. The audio session is activated in `provider(_:didActivate:)`, and libwebrtc's `RTCAudioSession` must use `useManualAudio`/`isAudioEnabled` to work with CallKit.
- **WebSocket best practices:**
  - App-level ping every 10–25 s to keep CGNAT mappings alive (mobile NAT UDP/TCP idle timeouts can be short)
  - automatic reconnect with exponential backoff and jitter, triggered by `NWPathMonitor` changes
  - session resume tokens so in-flight call state survives a socket reconnect
  - queue ICE candidates and restart offers until the socket is back
  - URLSessionWebSocketTask or Network.framework `NWConnection` with WebSocket options both work
  - serve on 443 behind the same TLS terminator as the API
- **macOS:** no such background restriction, but use the same PushKit/CallKit-equivalent flows (CallKit is available on macOS 13+, from prior knowledge, verify) for consistency.
- Pre-warm: on a VoIP push, start fetching TURN credentials and creating the peer connection before the user answers. This cuts post-answer setup to about 1 RTT plus ICE checks.

### Gaps
- The Apple docs page content wasn't retrieved (rendered empty), so the iOS 13+ "must report to CallKit" rule is cited from prior knowledge plus the doc URL.
- No primary measurements of mobile carrier NAT idle timeouts (to set keepalive intervals) were gathered.

---

## Recommendation summary (synthesis of the above)
1. **TURN:**
   - Run **coturn 4.18+** in Docker Compose (`network_mode: host`) on a dual-stack Hetzner or OVH VM.
   - Listeners: UDP 3478, TCP 3478, TLS 443 (second IP, or SNI multiplexing with the HTTPS proxy). Restrict the relay port range.
   - Use `use-auth-secret` with REST-style ephemeral credentials issued by the Go API (TTL about 1–12 h).
   - Set `denied-peer-ip` for private, loopback, metadata and CGNAT ranges, plus per-user quotas.
   - Add **Cloudflare TURN** as a secondary `iceServers` entry. It costs $0.05/GB, which is effectively free for audio, and gives anycast on 443.
   - Go-native alternative: embed **Pion TURN** (MIT) when you want per-user policy in code. If you adopt LiveKit, its built-in TURN/TLS covers SFU traffic.
2. **Relay budget:**
   - Assume about 20% relayed for plain P2P, higher with privacy defaults.
   - Relayed audio costs about 22–36 MB/h, cents per thousand hours on flat-rate hosts.
   - Measure with `getStats` (selected candidate-pair `candidateType == relay` and the relay protocol).
3. **Reliability:**
   - Use continual gathering, ICE restart on `disconnected` for more than about 2–3 s or on `failed`, and proactive restart on `NWPathMonitor` interface change.
   - Use a resilient WebSocket with resume, a CallKit-held "Reconnecting…" state, and give up at about 30 s (in line with the RFC 7675 consent window).
   - Use `iceTransportPolicy = relay` for non-contacts or as a privacy toggle.
4. **SFU:** **LiveKit** (Go, Apache-2.0, Swift SDK, E2EE, built-in TURN). Avoid ion-sfu (dormant), Janus (GPL plus C integration), and JVB (heavy Java/XMPP). Choose mediasoup only if you'd accept a Node sidecar.
5. **Architecture:**
   - Prototype both "LiveKit for all calls" and "custom P2P 1:1 + LiveKit for groups".
   - Default to LiveKit for everything unless P2P's latency and bandwidth savings measurably matter, because it removes most of the reconnection, TURN and E2EE engineering burden.
   - Keep low-data tuning (Opus 16–24 kbps, DTX, larger ptime if exposed, no video by default) as a hard requirement to verify in the Swift SDK.
