# E2EE Options for a Self-Hosted Messaging + Calling Platform (Go server, Swift clients, later Kotlin) — as of Oct 2026

Scope: reviewed, practical E2EE implementations for 1:1 and group messaging, 1:1 and SFU group call media, local data at rest, encrypted backups, and audit cost. Labels used below: "[verified]" means checked against a primary source this session. "[unverified]" means background knowledge that was not re-checked this session; confirm it before relying on it.

## Q1. libsignal (Signal Protocol): license, bindings, third-party support, PQ status, Go server needs

### Takeaway
libsignal is the most battle-tested and post-quantum-advanced option (PQXDH plus the 2025 SPQR "Triple Ratchet"). However, it is **AGPL-3.0-only**, Signal **explicitly does not support use outside Signal**, and its APIs change without notice. For a small product that might ship a closed-source client, or that wants a stable dependency, this makes it a poor fit.

### Cited Findings
- License: the libsignal repo LICENSE file is the GNU Affero General Public License v3 [verified]. The Swift podspec declares `s.license = 'AGPL-3.0-only'`, version `0.106.0`, minimum platform iOS 15.0. It is distributed by git tag with a prebuilt FFI archive, not via CocoaPods trunk — [LICENSE](https://github.com/signalapp/libsignal/blob/main/LICENSE); [LibSignalClient.podspec](https://github.com/signalapp/libsignal/blob/main/LibSignalClient.podspec)
- Bindings: "platform-agnostic APIs used by the official Signal clients and servers, exposed as a Java, Swift, or TypeScript library. The underlying implementations are written in Rust." Android/Java artifacts come from Signal's own Maven repo, `https://build-artifacts.signal.org/libraries/maven/`. There is no separate Kotlin API; Kotlin uses the Java library — [libsignal README](https://github.com/signalapp/libsignal/blob/main/README.md)
- Third-party support: "**Use outside of Signal is unsupported.** ... All APIs and implementations are subject to change without notice, as are the JNI, C, and Node add-on 'bridge' layers." Breaking changes to Java/Swift/TS APIs are reflected in version numbers only "on a best-effort basis" — [libsignal README](https://github.com/signalapp/libsignal/blob/main/README.md)
- PQXDH: spec Revision 3 (2023-05-24, last updated 2024-01-23). It provides "post-quantum forward secrecy" but "still relies on the hardness of the discrete log problem for mutual authentication". The example pqkem is CRYSTALS-Kyber-1024. It is designed for the case where "Bob is offline but has published some information to a server" — [Signal PQXDH spec](https://signal.org/docs/specifications/pqxdh/)
- SPQR / Triple Ratchet (announced 02 Oct 2025): a new "Sparse Post Quantum Ratchet" based on ML-KEM runs alongside the Double Ratchet, and the two keys are mixed via a KDF. "An attacker has to break both our elliptic curve and ML-KEM". ML-KEM-768 encapsulation keys are 1184 bytes versus 32 bytes for ECDH, so SPQR spreads (chunks) them across messages to save bandwidth — [Signal blog: SPQR](https://signal.org/blog/spqr/)
- Go alternatives to libsignal: `go.mau.fi/libsignal` (used by whatsmeow) is listed as **GPL-3.0** in a dependency report — [licenses.dev whatsmeow deps](https://licenses.dev/go/go.mau.fi%2Fwhatsmeow/v0.0.0-20240603101645-64bc969fbe78). It derives from RadicalApp's libsignal-protocol-go, whose README links to RadicalApp godoc — [tulir/libsignal-protocol-go](https://github.com/tulir/libsignal-protocol-go). The RadicalApp-derived packages are also GPL-3.0 — [pkg.go.dev polymorfa/libsignal-protocol-go](https://pkg.go.dev/github.com/polymorfa/libsignal-protocol-go)

### Inferences
- **AGPL implications.**
  - **Client:** a closed-source iOS/Android client that links libsignal would violate AGPL. Clients must ship complete corresponding source under AGPL-compatible terms. There is also a long-standing (unresolved) debate about whether the App Store's terms are compatible with (A)GPL; Signal sidesteps it as the copyright holder.
  - **Server:** a Go server that only stores and forwards opaque blobs does not link libsignal, so AGPL does not reach it. If the server uses libsignal via cgo (for example to verify XEdDSA signatures or to issue sealed-sender certificates), the server becomes a derivative work. AGPL §13 (network use) would then require offering source to users.
  - None of this is legal advice; get counsel.
- **Server pieces you would build in Go with Signal Protocol.** These are inferred from the PQXDH "publish to server" model plus [unverified] knowledge of Signal-Server:
  - per-device identity key registry;
  - signed EC prekey storage;
  - one-time EC prekeys;
  - signed Kyber/ML-KEM prekeys, including a "last-resort" one, plus one-time PQ prekeys;
  - atomic prekey-bundle fetch-and-consume;
  - prekey-replenish signals;
  - device lists per account (multi-device = fan-out to each device);
  - offline message queue;
  - sealed sender (server issues short-lived sender certificates signed by a server key; delivery is authenticated by unidentified-access keys instead of the sender's identity).
- **Groups:** Signal's group model (Sender Keys plus a zkgroup-based group server) adds a lot of server-side cryptography (zero-knowledge credentials). The Go server would have to re-implement that or call libsignal.
- The Go Signal ports above are GPL-3.0 and unaudited as far as found. Treat them as unsuitable for a "reviewed implementation" requirement.

### Gaps
- No public statement was found that Signal offers a commercial or alternative license for libsignal. I could not confirm either way.
- Signal-Server's license was not retrieved this session ([unverified]: AGPL-3.0).
- No evidence was found on whether `go.mau.fi/libsignal` implements PQXDH or SPQR, or has been audited.
- No public third-party audit report specific to libsignal-the-library (as opposed to the protocol, which has extensive academic analysis) was retrieved this session.

## Q2. Double-ratchet alternatives: vodozemac (Olm/Megolm), libolm deprecation, Go implementations

### Takeaway
vodozemac is the permissively licensed (**Apache-2.0**), audited Rust implementation of Olm (1:1 double ratchet) and Megolm (group sender ratchet). It replaced libolm, which was deprecated in Aug 2024 and has timing-side-channel CVEs. Swift and Kotlin access is via matrix-rust-sdk's UniFFI bindings (Apache-2.0), but those bindings wrap the whole Matrix SDK rather than standalone vodozemac. Go has only an experimental, unaudited pure-Go Olm (goolm, MIT).

### Cited Findings
- vodozemac: "a pure Rust implementation of the Olm and Megolm cryptographic ratchets". Also provides SAS verification and the MSC4108 integrated encryption scheme. Current version `0.11.1`, `license = "Apache-2.0"`, `rust-version = "1.96"` — [vodozemac README](https://github.com/matrix-org/vodozemac); [Cargo.toml](https://github.com/matrix-org/vodozemac/blob/main/Cargo.toml)
- Audit: "This crate has received one security audit by Least Authority, with no significant findings." The audit was sponsored by Gematik in 2022 — [vodozemac README](https://github.com/matrix-org/vodozemac); [audit PDF](https://matrix.org/media/Least%20Authority%20-%20Matrix%20vodozemac%20Final%20Audit%20Report.pdf); [Matrix blog](https://matrix.org/blog/2024/08/libolm-deprecation/)
- libolm deprecation (Aug 2024):
  - Matrix says "libolm's primitives are functionally correct, but not resilient to timing attacks".
  - CVEs: CVE-2024-45191 (AES cache-timing), CVE-2024-45192 (Base64 timing side-channel), CVE-2024-45193 (malleable Ed25519 signatures).
  - All Element, Element X, Fractal, iamb and other matrix-rust-sdk clients already use vodozemac.
  - [Matrix.org: Libolm Deprecation](https://matrix.org/blog/2024/08/libolm-deprecation/)
- Swift: Matrix publishes a "Swift package for Matrix Rust components" (matrix-rust-components-swift), generated from matrix-rust-sdk. The matrix-rust-sdk LICENSE is the Apache License — [matrix-rust-components-swift](https://github.com/matrix-org/matrix-rust-components-swift); [matrix-rust-sdk LICENSE](https://github.com/matrix-org/matrix-rust-sdk/blob/main/LICENSE)
- Go: goolm is "a pure Go implementation of libolm", licensed MIT, and vendored in mautrix-go as `crypto/goolm`. The mautrix-go changelog calls it an "experimental pure Go Olm implementation to replace libolm" enabled by the `goolm` build tag. A later changelog entry fixed a "panic when processing certain malformed Olm messages" — [mautrix-go goolm README](https://github.com/mautrix/go/tree/main/crypto/goolm); [codeberg DerLukas/goolm](https://codeberg.org/DerLukas/goolm)
- Nixpkgs packaging notes that goolm "is not recommended by the mautrix developers" for production-critical use — [nixpkgs commit](https://code.fizz.buzz/talexander/nixpkgs/commit/b99b5e51b67239b147b697bec0626d67cc98092a) (secondary source; could be outdated)

### Inferences
- Olm/Megolm gives Signal-like 1:1 security plus a Sender-Keys-style group ratchet, under a permissive license, with an audited core.
  - Multi-device is handled by encrypting Megolm session keys to each device over Olm. That is O(devices) per key rotation, as in Matrix.
  - Megolm has weaker post-compromise security for groups than MLS: a group session is not healed until it is rotated.
- No post-quantum variant of Olm/Megolm was found shipped in vodozemac. This would be a disadvantage versus libsignal/SPQR if PQ is a requirement.
- Using vodozemac standalone from Swift/Kotlin means writing your own UniFFI wrapper. The alternative is adopting the full Matrix stack (matrix-rust-sdk crypto plus a Matrix homeserver), which conflicts with a custom Go server.
- The Go server does not need Olm at all: it stores one-time keys and fallback keys and relays ciphertext. So goolm's immaturity matters only if the server must take part in crypto.

### Gaps
- I found no official "standalone vodozemac Swift/Kotlin bindings" package. Confirm whether `matrix-sdk-crypto-ffi` can be used without the rest of the SDK.
- I found no audit of goolm.

## Q3. MLS (RFC 9420): libraries, licenses, bindings, audits, Go status, deployments, Delivery Service requirements

### Takeaway
MLS is the standards-track choice for multi-device groups: each device is a leaf, there is efficient O(log n) rekeying, and the group exporter can feed call keys. The two practical Rust libraries are **mls-rs** (AWS, **Apache-2.0 OR MIT**, ships UniFFI Swift/Kotlin bindings and an Apple CryptoKit provider) and **OpenMLS** (**MIT**, v0.9.0, used by Wire and XMTP). Go has no production MLS library; cisco/go-mls is a draft-era prototype. The server only needs to be a Delivery Service: a KeyPackage store plus strictly ordered fan-out of handshake messages.

### Cited Findings
- **mls-rs:**
  - Version `0.56.0`, `license = "Apache-2.0 OR MIT"`.
  - Workspace includes `mls-rs-uniffi` (UniFFI generates Swift and Kotlin), `mls-rs-ffi`, `mls-rs-crypto-cryptokit` (Apple CryptoKit), `-openssl`, `-awslc`, `-rustcrypto`, `-webcrypto`, `mls-rs-provider-sqlite` and `mls-rs-identity-x509`.
  - [mls-rs Cargo.toml](https://github.com/awslabs/mls-rs/blob/main/mls-rs/Cargo.toml); [workspace Cargo.toml](https://github.com/awslabs/mls-rs/blob/main/Cargo.toml)
- mls-rs crypto-provider table: OpenSSL supports cipher suites 1–7 (stable), AWS-LC supports 1, 2, 3, 5, 7 (stable), and RustCrypto supports 1, 2, 3 ("⚠️ Experimental"). The README also lists features: propose-then-commit, pre-computed key packages, PSKs, subgroup branching and reinit — [mls-rs README](https://github.com/awslabs/mls-rs)
- **OpenMLS:**
  - `license = "MIT"`, version `0.9.0`. "OpenMLS does not implement its own cryptographic primitives"; it uses pluggable providers. Targets include wasm32 and Android.
  - [openmls README](https://github.com/openmls/openmls); [openmls Cargo.toml](https://github.com/openmls/openmls/blob/main/openmls/Cargo.toml); [LICENSE](https://github.com/openmls/openmls/blob/main/LICENSE)
- OpenMLS security record:
  - Advisory GHSA-qr9h-x63w-vqfm: secret-tree private key material was not updated in storage during processing, affecting forward secrecy. Fixed in 0.7.1.
  - A missing length check meant membership and confirmation tags might not be verified correctly. Fixed in 0.7.2 / 0.8.0.
  - NCC Group reviewed libxmtp, which is "underpinned by OpenMLS" (Oct 2024). That was an application review, not an audit of OpenMLS itself.
  - [GitLab advisory DB](https://advisories.gitlab.com/pkg/cargo/openmls/GHSA-qr9h-x63w-vqfm/); [NCC Group XMTP report](https://www.nccgroup.com/research/public-report-xmtp-mls-implementation-review/)
- **Cisco MLS++:** C++17, OpenSSL 1.1.1/3.0 or BoringSSL. The README still links the *draft* protocol doc — [cisco/mlspp](https://github.com/cisco/mlspp)
- **cisco/go-mls:** "Right now, this is just a Go library that implements the core protocol. It is missing key things like message sequencing, deconfliction, and retransmission. The interface should not be considered stable. The most you can really do with it is run the tests." — [cisco/go-mls README](https://github.com/cisco/go-mls)
- **Delivery Service (RFC 9750, MLS Architecture):**
  - Clients upload KeyPackages to the DS. To add someone, a client "contacts the DS to request KeyPackages for each of the other clients".
  - The DS "should provide the minimum number of KeyPackages necessary".
  - KeyPackages are version-specific and signed.
  - [RFC 9750](https://www.rfc-editor.org/rfc/rfc9750.txt)
- **Deployments:**
  - **Discord DAVE:** MLS for call key exchange, with ciphersuite 2 (DHKEMP256_AES128GCM_SHA256_P256) and basic credentials. The voice gateway is the MLS DS and an "external sender" that issues Add/Remove proposals. Clients have preferred E2EE since Sept 2024, and the README says it would be an enforced requirement for all official clients in 2025 — [DAVE whitepaper](https://daveprotocol.com/); [discord/dave-protocol](https://github.com/discord/dave-protocol)
  - **Cisco Webex:** used a draft MLS as the foundation of "Zero Trust Security for Webex Meetings" and stated a plan to upgrade to the RFC version. I could not confirm whether that happened — [Webex blog](https://blog.webex.com/hybrid-work/scalable-end-to-end-security-in-webex/)
  - **RCS:** GSMA RCS Universal Profile 3.0 (March 2025) specifies MLS for E2EE. Apple (iOS 26.5) and Google Messages began a cross-platform E2EE RCS beta in May 2026, on by default and with carrier support required. Sources are secondary — [Infosecurity Magazine](https://www.infosecurity-magazine.com/news/apple-google-rcs-end-to-end/); [The Hacker News (GSMA, Mar 2025)](https://thehackernews.com/2025/03/gsma-confirms-end-to-end-encryption-for.html); [Android Authority](https://www.androidauthority.com/apple-ios-26-rcs-end-to-end-encryption-mls-protocol-3588258)
    - One search summary also claimed that "the service provider ... can still read the messages". That contradicts E2EE and is likely a garbling; treat it as suspect.
  - **Wire:** self-hosted wire-server docs still describe MLS as "an opt-in feature" that a team admin must enable. The page is flagged as not the latest docs version — [Wire docs: MLS](https://docs.wire.com/latest/understand/mls.html)

### Inferences
- **Go server as an MLS Delivery Service** needs:
  1. KeyPackage upload/claim API, ideally with a last-resort KeyPackage per device.
  2. Per-group strictly ordered acceptance of Commits: first-commit-wins per epoch, reject stale epochs. This resolves the "sequencing/deconfliction" problem that go-mls left out.
  3. Fan-out of Commits, Welcomes and application messages to all member devices.
  4. Optional server-side checks of the public MLSMessage framing (group_id, epoch, sender leaf). DAVE's gateway does this by keeping only "basic MLS group context (group ID, epoch, and MLS ciphersuite)". Parsing that framing in Go is a small TLS-presentation-language codec, not cryptography.

  An Authentication Service (binding device signature keys to user identities, e.g. via key transparency or safety numbers) is also needed. MLS deliberately leaves the AS out of scope.
- **Clients:** mls-rs is currently the best fit for Swift and Kotlin. It has first-party UniFFI bindings in-repo, a CryptoKit backend, a SQLite storage provider and a dual permissive license.
  - OpenMLS has a larger ecosystem: Wire's core-crypto [unverified] wraps it with Swift/Kotlin bindings, under GPL-3.0 [unverified]. However, OpenMLS itself ships no first-party Swift bindings.
- **Multi-device:** MLS treats every device as a separate leaf, so linked devices fall out naturally. Adding a new device requires an Add plus a Commit by an existing member, or an External Commit.
- **Post-quantum:** RFC 9420 ciphersuites are classical. PQ/hybrid MLS ciphersuites were still in IETF drafts as of the latest info I have [unverified]. If PQ matters, plan for crypto agility (MLS supports ReInit to change ciphersuites).

### Gaps
- No public third-party audit report was found for mls-rs or for OpenMLS as standalone libraries (OpenMLS has formal-methods work [unverified]).
- cisco/go-mls repo activity and archive status could not be retrieved (GitHub API returned nothing).
- I could not confirm Apple's use of MLS outside RCS.
- I could not confirm whether Webex completed the RFC upgrade, or Wire's current default-MLS status in its cloud product.

## Q4. Call media E2EE: DTLS-SRTP, SFrame, native libwebrtc transforms, DAVE, LiveKit

### Takeaway
For 1:1 P2P/TURN calls, standard DTLS-SRTP is already end-to-end, provided the DTLS fingerprints are authenticated over the E2EE messaging channel. For SFU group calls you need frame-level E2EE: encrypt encoded frames before RTP packetization using **SFrame (RFC 9605)** or a DAVE-style AES-GCM scheme, with per-sender keys exported from the MLS group of call participants. DAVE is the best public production blueprint for this.

### Cited Findings
- **SFrame (RFC 9605, Aug 2024, Standards Track):** "end-to-end encryption and authentication mechanism for media frames in a multiparty conference call, in which central media servers (SFUs) can access the media metadata needed to make forwarding decisions without having access to the actual media." It is independent of RTP and applied to whole frames for bandwidth efficiency. Authors are from Apple, Cisco and others — [RFC 9605](https://www.rfc-editor.org/rfc/rfc9605.txt)
- **DAVE design:**
  - Members export "a ratcheted per-sender symmetric media encryption key" from the MLS group. On membership change the gateway drives a new epoch and new per-sender ratchets.
  - "Using the WebRTC encoded transform API, media senders encrypt encoded audio or video frames ... The encrypted frame ... is then packetized by WebRTC to one or more RTP packets ... sent to the SFU."
  - Receivers keep previous-epoch keys for up to 10 s during transitions.
  - Media cipher is AES128-GCM. Sender key ratchets come from the MLS exporter, "derived similarly to the MLS sender ratchet". The generation is taken from the most-significant byte of a truncated sync nonce.
  - [DAVE whitepaper](https://daveprotocol.com/)
- **DAVE security review:** Trail of Bits reviewed the implementation. Its finding TOB-DISCE2EC-5 notes that AES-GCM is not key-committing, which allows a low-severity multi-key ciphertext attack during transitions — [DAVE whitepaper §Media Ciphersuite](https://daveprotocol.com/)
- **DAVE out-of-band verification:** clients display an MLS **epoch authenticator** as a 30-digit code (groups of 5) for out-of-band verification. **libdave** provides JS and C++ libraries "leveraged by Discord's native clients" — [DAVE whitepaper](https://daveprotocol.com/); [discord/libdave](https://github.com/discord/libdave)
- **LiveKit E2EE:**
  - Available self-hosted at no extra cost and covers media plus data channels.
  - "In many cases you can use a built-in key provider with a single shared key for the whole room. If you require unique keys for each participant, or key rotation ... implement your own key provider."
  - "It is your responsibility to securely generate, store, and distribute encryption keys ... LiveKit does not (and cannot) store or transport encryption keys."
  - Its example of generating the shared key "on your server" and distributing it with the access token is **not** E2EE against the server operator.
  - [LiveKit docs: Encryption overview](https://docs.livekit.io/home/client/tracks/encryption/)

### Inferences
- **Recommended call architecture:**
  - **1:1:** WebRTC DTLS-SRTP, with the DTLS certificate fingerprints carried inside E2EE signaling messages so that TURN or the server cannot man-in-the-middle the call.
  - **Group:** an SFU (LiveKit, Apache-2.0 [unverified], or Pion-based in Go) with frame E2EE. Keys come from an MLS group per call (or the conversation's MLS group exporter, with a "call" label), with per-sender ratchets as in DAVE, delivered through your own LiveKit custom key provider.
  - **Never** use LiveKit's server-generated shared key in "strict E2EE" mode; that is reasonable only for the clearly labeled server-readable mode.
- **Native iOS:** Google's libwebrtc exposes `FrameEncryptorInterface`/`FrameDecryptorInterface` in C++, and the Objective-C SDK has frame-crypto hooks [unverified]. LiveKit's Swift SDK wraps an equivalent "FrameCryptor" [unverified]. Either avoids writing your own media-path hooks.
- **Cipher choice:** prefer SFrame's standardized format (with AES-GCM or AES-CTR+HMAC suites) over inventing a new frame format, to avoid custom crypto. DAVE's choice of truncated tags and nonces shows the engineering pitfalls (codec-aware unencrypted ranges, AV1/H.264 packetizer quirks).

### Gaps
- I could not confirm the current libdave license, or whether it ships Swift/Kotlin bindings.
- I did not verify LiveKit's Swift and Android SDK E2EE feature parity or its frame format (SFrame vs. custom) this session.
- I did not verify which libwebrtc/WebRTC-SDK version exposes FrameEncryptor in Swift.

## Q5. Local data at rest on iOS

### Takeaway
Use iOS Data Protection (file protection classes) and the Keychain as the baseline. Add SQLCipher (BSD-style community license) or an app-level AEAD layer only if you need encryption keyed independently of the device passcode, or a cross-platform database format shared with Android.

### Cited Findings
- SQLCipher's community edition LICENSE is a BSD-style license, "Copyright (c) 2025, ZETETIC LLC ... Redistribution and use in source and binary forms, with or without modification, are permitted provided ..." — [SQLCipher LICENSE.md](https://github.com/sqlcipher/sqlcipher/blob/master/LICENSE.md)
- mls-rs ships `mls-rs-provider-sqlite` for MLS state storage — [mls-rs workspace](https://github.com/awslabs/mls-rs/blob/main/Cargo.toml)

### Inferences (Apple platform facts below are [unverified] this session; see Apple Platform Security guide: https://support.apple.com/guide/security/welcome/web)
- File protection classes are `NSFileProtectionComplete`, `CompleteUnlessOpen`, `CompleteUntilFirstUserAuthentication` (the default) and `None`.
  - A messaging app that must decrypt pushes in a Notification Service Extension while the device is locked typically cannot use `Complete` for its message DB. A common pattern is `CompleteUntilFirstUserAuthentication` for the DB, plus `Complete` for media and attachments.
- Store long-term identity and DB keys in the Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. The `ThisDeviceOnly` setting prevents migration into iCloud Keychain and backups.
- The Secure Enclave only holds P-256 keys. It cannot hold X25519/Ed25519 identity keys, so use it to wrap a DB key, not as the protocol identity key.
- SQLCipher adds value mainly when combined with an app passcode, or for an Android-parity DB format. On Android, the analogue is the Android Keystore plus SQLCipher or Room with encryption.

### Gaps
- Apple documentation was not fetched this session.
- Whether SQLCipher's commercial editions are required for App Store builds (licensing or FIPS) was not checked.

## Q6. Encrypted backups

### Takeaway
Big platforms use either a device-generated high-entropy recovery key (Signal, 2025) or HSM-enforced PIN or password guessing limits (WhatsApp/Meta Backup Key Vault, Signal SVR). For a small self-hosted project, the **simplest safe option is Signal's model**: a client-generated 256-bit recovery key shown once to the user, with no server-side secret. Avoid PIN or low-entropy passphrases unless you can run tamper-resistant guess-limiting hardware.

### Cited Findings
- **Signal Secure Backups (08 Sep 2025):**
  - Opt-in. At its core is "a 64-character recovery key that is generated on your device ... never shared with Signal's servers ... the only way to 'unlock' your backup. Losing it means losing access to your backup permanently."
  - Archives are refreshed daily.
  - Free tier covers all text plus 45 days of media; $1.99/month for more media.
  - Uses zero-knowledge credentials so archives are not linked to the account or the payment.
  - [Signal blog](https://signal.org/blog/introducing-secure-backups/); [Signal support](https://support.signal.org/hc/en-us/articles/9708267671322-Signal-Secure-Backup)
- **Signal SVR:** SVR backs the Signal PIN for profile, settings and contacts, but "cannot be used to recover lost chat history". SVR3 distributes trust across hardware enclaves from different cloud providers — [IACR ePrint 2024/887](https://eprint.iacr.org/2024/887); [Signal support PIN article](https://support.signal.org/hc/en-us/articles/360007059792)
- **WhatsApp/Messenger (Meta):**
  - A device-generated key encrypts backups. An HSM-based Backup Key Vault stores the key behind OPAQUE password authentication and enforces attempt limits before making the key permanently inaccessible.
  - A May 2026 Meta post describes a geographically distributed HSM fleet with majority-consensus replication. Fleet public keys are pinned in the app (WhatsApp) or delivered in a bundle signed by Cloudflare and counter-signed by Meta (Messenger).
  - [Meta Engineering, May 2026](https://engineering.fb.com/2026/05/01/security/meta-strengthening-end-to-end-encrypted-backups/); [The Hacker News 2021](https://thehackernews.com/2021/09/whatsapp-to-finally-let-users-encrypt.html)

### Inferences
- HSM- or enclave-based PIN recovery requires attested, tamper-resistant infrastructure plus a separate audit. That is out of reach for a small self-hosted project, and self-hosters cannot attest each other's HSMs.
- Recovery-key design:
  - Generate 32 random bytes on the client.
  - Display the key as a word or grouped string.
  - Derive the backup encryption and MAC keys (or an AEAD key) via HKDF.
  - Upload only ciphertext.
  - Optionally store the recovery key in iCloud Keychain (user opt-in) for convenience.
- A passphrase option, if offered, must use a memory-hard KDF (Argon2id) with high parameters, and its weakness should be stated in the UI.
- Multi-device message history sync can reuse the same mechanism: encrypted archive plus key transfer over an E2EE device-link channel.

### Gaps
- Meta's whitepaper "Security of End-To-End Encrypted Backups" was not fetched directly.
- Signal's exact backup KDF and cipher details were not retrieved.

## Q7. Security audit costs and providers

### Takeaway
Published pricing is scarce. The only concrete public figures found are dated (2020): about $16k per engineer-week at Trail of Bits, and about $64k for a "decent" scoped cryptography audit, with rates up 20–25% by 2022. Budget roughly mid-five to low-six figures for a focused review of protocol integration plus clients. **OTF's Red Team Lab funds audits at no cost** for open-source internet-freedom projects, using firms such as Cure53, Trail of Bits and Radically Open Security.

### Cited Findings
- Firo/Zcoin (2020): a decent Trail of Bits audit was quoted at ~$64k USD. A limited two-engineer-week implementation review was budgeted at $32k. SmartDEC was quoted at $14k for a wallet/crypto-library review. Least Authority was described as "pretty good ... but cost wise they're the highest" (forum opinion) — [Firo forum](https://forum.firo.org/t/raising-funds-for-an-audit-of-lelantus/695)
- Trail of Bits reportedly raised fees 20–25% (2022) — [NYSSCPA](https://nysscpa.org/news/1045850-crypto-security-auditing-now-a-lucrative-practice-2022-08-23)
- Cure53's TweetNaCl-js review (2017) took three work days, showing that small libraries can be scoped in days — [Cure53 report](https://tweetnacl.js.org/audits/cure53.pdf)
- OTF Red Team Lab:
  - Free, in-kind audits: code audits, cryptographic design reviews and architecture reviews.
  - Projects without OTF funding may apply if relevant to internet freedom.
  - Findings are published after responsible disclosure.
  - Past vendors include Cure53, Include Security, Radically Open Security, Subgraph, Trail of Bits, 7asecurity and the Eaton Cybersecurity SAFE Lab.
  - Apply at apply.opentech.fund.
  - [OTF Security Lab](https://www.opentech.fund/labs/security-lab/); [OTF guide](https://guide.opentech.fund/our-labs/red-team-lab); [Civic Tech Field Guide](https://civictech.guide/listing/red-team-lab-at-open-tech-fund/)
- Reference engagements: Least Authority audited vodozemac (sponsored by Gematik); Trail of Bits reviewed Discord DAVE; NCC Group reviewed libxmtp/OpenMLS integration — [vodozemac README](https://github.com/matrix-org/vodozemac); [DAVE](https://daveprotocol.com/); [NCC Group](https://www.nccgroup.com/research/public-report-xmtp-mls-implementation-review/)

### Inferences
- Using an already-audited library (vodozemac, or the pattern of mls-rs plus DAVE) narrows the audit scope to *your integration*: key management, DS ordering, the device-linking flow, call key derivation, backup format and storage. That is the cheapest way to get meaningful assurance.
- An open-source client and server materially improve the odds of a free OTF audit. That is another reason to prefer permissive (Apache/MIT) libraries, which keep both open- and closed-source options open.

### Gaps
- No current (2025–2026) rate cards were found for NCC Group, Trail of Bits, Cure53, Least Authority or Radically Open Security. All quotes are by engagement; contact the firms directly.
- No current OTF funding caps or deadlines were found.

## Comparison summary and recommendation

### Takeaway
**Recommended stack:**
- **MLS via mls-rs** (Apache-2.0 OR MIT; UniFFI Swift/Kotlin; CryptoKit backend) for all conversations (1:1 = 2-member group), with per-device leaves for multi-device.
- A **Go server acting as MLS Delivery Service** (KeyPackage store, ordered commit sequencer, fan-out; no decryption).
- **Group call media** keyed from an MLS exporter with per-sender ratchets and SFrame-style frame encryption through a custom key provider, following the DAVE blueprint.
- **DTLS-SRTP** for 1:1 calls, with fingerprints authenticated over MLS.

The fallback for Signal-style 1:1 plus Sender Keys is **vodozemac** (Apache-2.0, audited). Avoid libsignal unless you commit to AGPL clients and accept unsupported, churning APIs.

### Cited Findings (comparison matrix — sources in sections above)

| Option | License | Swift | Kotlin/Android | Go server need | Audit / maturity | PQ |
|---|---|---|---|---|---|---|
| libsignal | AGPL-3.0-only | Official (podspec 0.106.0) | Java lib (Signal Maven) | Prekey store incl. Kyber prekeys, sealed-sender certs; groups need zkgroup | Signal production; "use outside Signal unsupported" | PQXDH + SPQR Triple Ratchet (2025) |
| vodozemac (Olm/Megolm) | Apache-2.0 | Via matrix-rust-sdk bindings (Apache-2.0); standalone needs own UniFFI | Same | One-time/fallback key store + relay; goolm (MIT) experimental | Least Authority audit, no significant findings; Matrix production | None found |
| mls-rs | Apache-2.0 OR MIT | `mls-rs-uniffi` + CryptoKit provider | `mls-rs-uniffi` | DS: KeyPackages + ordered commits | AWS; no public audit found | Classical RFC suites |
| OpenMLS | MIT | No first-party bindings | No first-party | Same DS | Wire/XMTP use; 2 advisories fixed; NCC reviewed XMTP integration | Classical |
| cisco/go-mls | (not checked) | — | — | — | README: missing sequencing, unstable, "run the tests" only | — |
| DAVE / libdave | (not checked) | C++ (bridge needed) | C++ | Gateway = DS + external sender | Trail of Bits reviewed; Discord production | Classical (P-256) |
| LiveKit E2EE | (server Apache-2.0, unverified) | Swift SDK | Android SDK | You distribute keys | Production SFU | n/a |

### Inferences
- **Why MLS over Signal-style for this product:**
  - Native multi-device (devices = leaves) and groups.
  - One key schedule serves both messaging and SFU call keys, as DAVE proves.
  - IETF standard (RFC 9420) adopted by Discord, Webex and GSMA RCS.
  - Permissive licenses.
  - A Go server needs no cryptographic library, only ordering and storage.
  - Main costs: MLS's DS ordering requirement, the need for an Authentication Service (identity verification UX, e.g. safety numbers or epoch-authenticator codes as in DAVE), and no shipped PQ ciphersuite yet.
- **Why not libsignal:** the AGPL-3.0-only license plus "Use outside of Signal is unsupported", APIs that change without notice, and heavy server-side requirements for groups (zkgroup) and sealed sender. It is the best choice only if PQ is mandatory today and fully AGPL clients are acceptable.
- **Server-readable mode:** implement it as a separate conversation type with no MLS group, TLS-only transport, a distinct UI color or banner, and no automatic downgrade path from E2EE conversations. Never let the server hold or derive MLS or call keys for strict-mode conversations. LiveKit's server-generated shared key is acceptable only in this mode.
- **No custom cryptography:**
  - Use the library's ciphersuites, SFrame (RFC 9605) for frames, and HKDF/Argon2id from vetted libraries for backups.
  - Every custom piece (DS ordering, key-provider glue, backup format) should be in the audit scope.
  - Apply to OTF Red Team Lab if open source; otherwise budget a scoped integration review.

### Gaps
- Licenses of libdave and cisco/go-mls were not confirmed.
- Audit status of mls-rs was not confirmed.
- I found no 2026 status on PQ MLS ciphersuite standardization or library support.
