# Call Quality at Very Low Data Usage over WebRTC (native libwebrtc, iOS/macOS + Go server) — state as of Oct 2026

Conventions used in all computations below: 1 KB = 1000 bytes; "kbps" = 1000 bit/s; figures are **one direction** (one sender's stream) unless stated. In a 1:1 call, a phone uploads its own stream and downloads the peer's, so per-device data use is roughly 2x the one-direction number. Cellular link-layer overhead (PDCP/RLC, any ROHC) is excluded. Source code references are to `webrtc.googlesource.com/src` `main` and `github.com/xiph/opus` `main`, read on 2026-10-27.

---

## 1. Opus settings: bitrate floors, frame size/ptime, DTX, in-band FEC (LBRR), DRED, deep PLC, complexity

### Takeaway
Opus wideband speech is solid from about 16–20 kbps. Between 9 and 12 kbps it is usable but clearly degraded. At 6 kbps it is poor: it runs narrowband, POLQA about 1.9. The Opus 1.5/1.6 ML decoder add-ons (Deep PLC, LACE/NoLACE, BWE) are decoder-only and backwards compatible, so they are the cheapest quality gain for the Ultra-low and Data-saver modes. Stock libwebrtc does not build or enable any of them. In-band FEC (LBRR) does not activate below about 13.7 kbps in libwebrtc. DRED takes its bits out of the target bitrate and is effectively off below about 12 kbps. Neither one helps the Ultra-low mode.

### Cited Findings
**Bitrate floors and bandwidth selection**
- libwebrtc's Opus wrapper documents the recommended bitrates as "16-20 kb/s for WB speech, 28-40 kb/s for FB speech, 48-64 kb/s for FB mono music". Its default bitrates are `kOpusBitrateNbBps = 12000`, `kOpusBitrateWbBps = 20000` and `kOpusBitrateFbBps = 32000` (mono; x2 for stereo) — [libwebrtc audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)
- libwebrtc clamps the Opus bitrate to `kMinBitrateBps = 6'000` .. `kMaxBitrateBps = 510'000`. The comment reads "Opus API allows a min bitrate of 500bps, but Opus documentation suggests bitrate should be in the range of 6000 to 510000" — [audio_encoder_opus_config.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_codecs/opus/audio_encoder_opus_config.h)
- libwebrtc bandwidth override (`GetNewBandwidth`). Above 11 kbps it uses OPUS_AUTO. Above 9 kbps it forces at least WIDEBAND. Below 8 kbps it forces NARROWBAND. This is gated by the field trial `WebRTC-AdjustOpusBandwidth` — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)
- libopus's own mono-voice bandwidth thresholds (middle value ± hysteresis) are: NB↔MB 9000±700, MB↔WB 9000±700, WB↔SWB 13500±1000, SWB↔FB 14000±2000 bps — [libopus src/opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c)
- Meta: "The lowest operating point for Opus is 6 kbps, at which it runs in NarrowBand mode (0–4kHz)". Meta gives POLQA MOS 1.89 for Opus against 3.9 for MLow at 6 kbps WB. Raw 48 kHz/16-bit mono is 768 kbps, which "modern codecs are able to compress down to 25-30 kbps" — [Meta Engineering, MLow, Jun 2024](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)
- Opus 1.5 (Mar 2024) added "Low-bitrate speech quality enhancement down to 6 kb/s wideband" (LACE/NoLACE), demonstrated on Opus wideband speech at 6, 9 and 12 kb/s — [libopus 1.5 release](https://opus-codec.org/release/stable/2024/03/04/libopus-1_5.html), [Opus 1.5 demo](https://opus-codec.org/demo/opus-1.5/)
- LACE is a DNN-tuned postfilter: "The audio itself never goes through the DNN… a small and very-low-complexity model… that can run even on older phones" — [Opus 1.5 demo](https://opus-codec.org/demo/opus-1.5/)
- Decoder complexity gates the ML features in libopus `opus_decoder.c`: `enable_deep_plc = complexity >= 5`, `complexity >= 6` selects LACE, `complexity >= 7` selects NoLACE, and the BWE needs complexity >= 4 plus `OPUS_SET_OSCE_BWE`. **The decoder's default complexity is 0.** — [libopus src/opus_decoder.c](https://github.com/xiph/opus/blob/main/src/opus_decoder.c)
- Opus 1.6 (current stable 1.6.1) adds an ML wideband-to-fullband speech BWE (8–20 kHz generated from 0–8 kHz without side information, compatible with any older Opus stream; "never activate for super-wideband or fullband audio"). It also adds 96 kHz "Opus HD", a 24-bit API and a "significant improvement to Deep Redundancy (DRED)" — [opus-codec.org news](https://opus-codec.org/), [Opus 1.6 demo](https://opus-codec.org/demo/opus-1.6/)
- The Opus 1.6 DRED model was retrained "with an improved loss function… Although it does not improve perceptual quality… the speech is more intelligible", measured at low bitrate — [Opus 1.6 demo](https://opus-codec.org/demo/opus-1.6/)

**Frame size / ptime**
- libwebrtc's supported Opus frame lengths are {10, 20, 40, 60} ms. 120 ms is only available when built with `WEBRTC_OPUS_SUPPORT_120MS_PTIME`. ANA frame lengths are {20, 40, 60(, 120)}. A requested `ptime` is rounded up to the next supported length. The default is 20 ms — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc), [audio_encoder_opus_config.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_codecs/opus/audio_encoder_opus_config.h)
- RFC 7587: `ptime` is the preferred packet duration, default 20. `maxptime` defaults to 120. Valid values are 3, 5, 10, 20, 40, 60 or multiples of an Opus frame size up to 120 — [RFC 7587](https://www.rfc-editor.org/rfc/rfc7587.txt)

**DTX**
- SILK DTX: `NB_SPEECH_FRAMES_BEFORE_DTX 10` ("eq 200 ms") and `MAX_CONSECUTIVE_DTX 20` ("eq 400 ms"). DTX starts after 200 ms of inactivity, and then one refresh frame is sent every 400 ms — [libopus silk/define.h](https://github.com/xiph/opus/blob/main/silk/define.h), [opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c)
- In libopus, "When encoding voice and DTX is enabled but the generalized DTX cannot be used, use SILK in order to make use of its DTX" (it forces SILK when voice_est > 100) — [opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c)
- RFC 7587: `usedtx` "specifies if the decoder prefers the use of DTX", default 0 — [RFC 7587](https://www.rfc-editor.org/rfc/rfc7587.txt)

**In-band FEC (LBRR): when it activates**
- `decide_fec()` returns 0 when useinbandfec is off, the loss is 0, or the mode is CELT-only. Otherwise it compares the equivalent rate to `fec_thresholds` = NB 12000, MB 14000, WB 16000, SWB 20000, FB 22000 (each ±1000 hysteresis), scaled by `(125 - min(loss%,25))/100`. If loss is ≤5% and the rate is too low, FEC stays off. If loss is >5%, the encoder **drops audio bandwidth step by step until FEC fits** — [opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c)
- When FEC is on and loss is high enough, libopus forces SILK mode (`packetLossPercentage > (128-voice_est)>>4`) — [opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c)
- libwebrtc feeds Opus a packet-loss rate smoothed by an exponential filter (time constant ≈10 s, alpha 0.9999 per ms) and capped at `kMaxPacketLossFraction = 0.2` — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)
- Meta, independently: Opus "needs a minimum of 19 kbps to encode any inband FEC at 10 percent packet loss… at these bitrates [6 kbps], Opus is not able to encode any inband FEC" — [Meta MLow](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)
- The LBRR payload measured by webrtcHacks takes "up to 10kbps (or two thirds of the data rate) under heavy packet loss". In their 20%-loss test, concealment was 18% with FEC, 20% without FEC, 4% with RED distance 1 and 0.7% with RED distance 2 — [webrtcHacks, RED](https://webrtchacks.com/red-improving-audio-quality-with-redundancy/)
- RFC 7587: FEC in packet N carries info about N-1, and "The decision to send in-band FEC information is entirely controlled by the encoder" — [RFC 7587](https://www.rfc-editor.org/rfc/rfc7587.txt)

**DRED (Deep REDundancy)**
- DRED uses an RDO-VAE to "transmit one second of redundancy with about 12-32 kb/s overhead". It targets burst losses, whereas LBRR protects only one previous frame — [Opus 1.5 demo](https://opus-codec.org/demo/opus-1.5/)
- In libopus, DRED **takes its bitrate out of the target**: `st->bitrate_bps -= dred_bitrate_bps`. With FEC on, `dred_frac = min(0.7, 3*loss)` and `bitrate_offset = 20000`. With FEC off, `dred_frac = min(0.8, 0.55+loss)` when loss is >5% (else `12*loss`) and `bitrate_offset = 12000`. The DRED target is `dred_frac*(bitrate − offset)`, scaled down for longer frames, and set to 0 "if we can't afford enough bits" (<2 chunks). It is enabled by `OPUS_SET_DRED_DURATION(x)` (in 10 ms units) — [opus_encoder.c](https://github.com/xiph/opus/blob/main/src/opus_encoder.c), [opus_defines.h](https://github.com/xiph/opus/blob/main/include/opus_defines.h)
- DRED is carried in the Opus padding/extension mechanism (extension ID 32). "Until adoption by the IETF, experimental implementations of DRED MUST use experiment extension ID 126". The current draft is draft-ietf-mlcodec-opus-dred-07 (Aug 2026, Standards Track, updates RFC 6716, expires 5 Feb 2027). Its reference implementation is "opus-1.6.1-20-gf18e26e64" (June 2026) — [draft-ietf-mlcodec-opus-dred-07](https://datatracker.ietf.org/doc/html/draft-ietf-mlcodec-opus-dred)
- DRED is gated behind `--enable-dred` (which pulls in `--enable-deep-plc`), costs about 2 MB of binary and about 1% CPU. It "is likely to keep the DRED implementation from being integrated officially into libWebRTC and the Chrome browser for the time being". The advice for 2026: "watch, do not deploy" — [BlogGeek.me DRED glossary, updated Oct 2026](https://bloggeek.me/webrtcglossary/dred/)
- A secondary source claims the DRED draft is in WG Last Call (Oct 2026) — unverified; see the search summary of [forasoft](https://www.forasoft.com/learn/audio-for-video/articles-audio/opus-codec-explained). The datatracker page does not confirm it.

**Deep PLC**
- Deep PLC is built with `--enable-deep-plc` (~1 MB) and enabled at run time by decoder complexity ≥5. It costs "about 1% of a laptop CPU core" at high loss. "Because deep PLC only affects the decoder, turning it on does not have any compat[ibility issues]" — [Opus 1.5 demo](https://opus-codec.org/demo/opus-1.5/)

**libwebrtc does not use any of the ML features out of the box**
- Chromium's `third_party/opus/BUILD.gn` defines no `ENABLE_DEEP_PLC`/`ENABLE_OSCE`/`ENABLE_DRED` (only `ENABLE_HARDENING` and similar). The pinned revision is `55513e81` — [Chromium third_party/opus BUILD.gn](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/third_party/opus/BUILD.gn), [README.chromium](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/third_party/opus/README.chromium)
- libwebrtc's `opus_interface.cc` calls `OPUS_SET_COMPLEXITY` only on the **encoder** (`WebRtcOpus_SetComplexity`). The decoder complexity is never set, so it stays at the libopus default of 0 and Deep PLC/LACE/NoLACE remain off even if compiled in. NetEq's PLC calls the Opus decoder in 10 ms PLC frames (`kWebRtcOpusPlcFrameSizeMs = 10`) — [opus_interface.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/opus_interface.cc)

**Complexity**
- libwebrtc's default encoder complexity is **5 on iOS/Android** and **9 on desktop**. `low_rate_complexity` is 9 if `WEBRTC_OPUS_VARIABLE_COMPLEXITY`, else the default. It switches around `complexity_threshold_bps = 12500 ± 1500` — [audio_encoder_opus_config.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_codecs/opus/audio_encoder_opus_config.h), [audio_encoder_opus_config.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_codecs/opus/audio_encoder_opus_config.cc)
- MLow's complexity is "10 percent lower than that of Opus". Over 20% of Meta calls are on ARMv7, and tens of millions of WhatsApp calls a day run on 10+-year-old devices — [Meta MLow](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)

### Inferences
- **Worked FEC thresholds** (from the libopus formula; "on" = threshold to switch FEC on, "stay" = threshold to keep it on):
  - WB: on at >20.4 kbps (5% loss), 19.55 (10%), 17.85 (20%, the libwebrtc cap); stay on >17.25 at 10%.
  - NB: on at >14.95 kbps (10%), 13.65 (20%).
  - libwebrtc caps reported loss at 20%. **LBRR therefore never activates below ~13.7 kbps** (equivalent rate). Between ~13.7 and ~17.9 kbps it activates only by dropping to NB/MB.
  - These match Meta's "19 kbps at 10% loss".
  - Mode impact: Ultra-low gets no FEC. In Data saver, FEC costs bandwidth (audio becomes narrowband under loss).
- **DRED in practice** (libopus formula, 20 ms frames, FEC off): at 24 kbps and 10% loss, about 0.65×12 = 7.8 kbps goes to DRED, leaving ~16 kbps for the primary. At 16 kbps it is ~2.6 kbps. At ≤12 kbps it is 0. DRED therefore helps the Balanced/High modes on bursty networks, not Ultra-low. Using it in libwebrtc means: building libopus 1.6.x with `--enable-dred`; patching `opus_interface.cc` to call `OPUS_SET_DRED_DURATION`; and, the hard part, teaching NetEq to call `opus_dred_parse()`/`opus_decoder_dred_decode()` on *future* packets during a gap. Both ends are your own app, so interop is not the blocker; the blockers are the draft status (experimental extension ID 126) and the NetEq work.
- **Cheapest big win for low modes:** build libopus 1.6.x into libwebrtc with deep PLC + OSCE (LACE/NoLACE) + BWE, and add one decoder CTL (`OPUS_SET_COMPLEXITY(7)`, plus `OPUS_SET_OSCE_BWE(1)` if wanted) in `WebRtcOpus_DecoderCreate`. This is receiver-only and bitstream-compatible. Expected CPU is on the order of ~1% of a core per stream for deep PLC (Xiph figure); NoLACE/BWE cost on A-series phones is not published (see Gaps).
- **Forcing wideband at 6–9 kbps.** To get the "6 kb/s wideband" behaviour that LACE/NoLACE target, do not let libwebrtc force NB below 8 kbps. Disable `WebRTC-AdjustOpusBandwidth` and set `OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND)`, which needs a small libwebrtc patch. libopus's own threshold would otherwise pick NB/MB below ~9 kbps.
- **ptime trade-off** (arithmetic): going from 20 to 60 ms adds 40 ms one-way packetization latency and cuts packets from 50/s to 16.7/s. At 6–12 kbps that cuts on-wire bytes by roughly 46–54% (see §2). One lost 60 ms packet removes 60 ms of speech, which is harder to conceal than 20 ms. 120 ms (+100 ms latency) only makes sense for the Ultra-low mode, and only with a custom-built libwebrtc.
- **Recommended Opus settings per mode** (codec bitrate = Opus target; mono; VBR; `application=VOIP`):

| Mode | Opus target | ptime | Bandwidth | DTX | In-band FEC | DRED | Decoder add-ons | Est. wire, 1 direction, continuous talk (IPv4, GCM, 2 exts) |
|---|---|---|---|---|---|---|---|---|
| Ultra-low 6–12 | 8–10 kbps (6 floor) | 60 ms (120 if built) | force WB ≥8k; NB at 6k | on | off (cannot activate) | off | Deep PLC + NoLACE (+BWE) | ~17–21 kbps @60 ms (≈130–160 KB/min); ~12–15 kbps @120 ms |
| Data saver 12–20 | 14–16 kbps | 40–60 ms | WB | on | on (activates ≥~14–18k under loss) | off | Deep PLC + NoLACE | ~25–30 kbps @40–60 ms (≈190–220 KB/min) |
| Balanced 20–32 | 24 kbps | 20–40 ms (adaptive_ptime) | WB/auto | on | on | optional (Opus 1.6, patched) | Deep PLC (+LACE) | ~38–51 kbps (≈280–385 KB/min) |
| High 32–64 | 32–48 kbps | 20 ms | auto (SWB/FB) | optional | on | optional | Deep PLC | ~59–75 kbps (≈445–565 KB/min) |

  With DTX and about 50% talk time, the active-speech numbers roughly halve (e.g. 12 kbps @60 ms: ≈84 KB/min against 158). Add RTCP/TWCC feedback (§2).

### Gaps
- I found no published CPU figures for Opus encoder complexity 0–10, NoLACE or BWE on Apple A-series. Measure with Instruments.
- I found no published POLQA/MOS table for Opus 1.5/1.6 with LACE/NoLACE at 6/9/12 kbps in text form. The Opus 1.5 demo shows plots/samples only, and they were not extracted here.
- The exact libopus `compute_equiv_rate` adjustment for 40/60 ms frames (which shifts the FEC thresholds slightly) was not computed.
- Opus 1.6's DRED bitrate/quality curves were not extracted (the demo page gives only qualitative intelligibility plots).

---

## 2. Packet header overhead (IP/UDP/RTP/SRTP/extensions/TURN/TLS) and RTCP — bytes/min per mode and ptime

### Takeaway
At 6–12 kbps with 20 ms packets, headers are 60–80% of the bytes on the wire. A typical libwebrtc audio packet carries about 68 bytes of overhead (IPv4). That alone is 27 kbps at 20 ms, 9 kbps at 60 ms and 4.5 kbps at 120 ms. ptime is therefore the biggest lever for the low modes, ahead of codec bitrate. Transport-wide-CC feedback adds about 2.7 kbps or more in the reverse direction, which matters a lot for audio-only Ultra-low.

### Cited Findings
- Header sizes:
  - IPv4 20 B / IPv6 40 B, UDP 8 B, RTP fixed header 12 B — [RFC 3550](https://www.rfc-editor.org/rfc/rfc3550)
  - RTP one-byte header extension block: 4-byte `0xBEDE` header + (1-byte ID/len + data) per element, padded to 32 bits — [RFC 8285](https://www.rfc-editor.org/rfc/rfc8285)
  - SRTP auth tag: 10 B for AES_CM_128_HMAC_SHA1_80 — [RFC 3711](https://www.rfc-editor.org/rfc/rfc3711), [RFC 4568](https://www.rfc-editor.org/rfc/rfc4568); 16 B for AEAD_AES_128_GCM — [RFC 7714](https://www.rfc-editor.org/rfc/rfc7714)
  - TURN ChannelData header 4 B, padded to a 4-byte multiple over TCP/TLS — [RFC 8656](https://www.rfc-editor.org/rfc/rfc8656)
  - TLS 1.3 record overhead: 5 B header + 1 B inner content type + 16 B AEAD tag — [RFC 8446](https://www.rfc-editor.org/rfc/rfc8446)
- libwebrtc `CryptoOptions` defaults: `enable_gcm_crypto_suites = true` (GCM, 16-byte tag, is preferred when both ends support it), `enable_aes128_sha1_32_crypto_cipher = false` (no 4-byte-tag suite), `enable_encrypted_rtp_header_extensions = true` — [api/crypto/crypto_options.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/crypto/crypto_options.h)
- Audio RTP header extensions libwebrtc can negotiate include `ssrc-audio-level` (1 B), `abs-send-time` (3 B), `transport-wide-cc` (2 B), `abs-capture-time`, `mid` and `rid` — [api/rtp_parameters.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/api/rtp_parameters.cc)
- libwebrtc accounts for per-packet transport overhead in audio rate control (`UpdateOverheadPerPacket`: transport overhead + `ExpectedPerPacketOverhead()`), and feeds it to the Opus encoder and ANA (`OnReceivedOverhead`). The field trial `WebRTC-Audio-LegacyOverhead` exists — [audio/audio_send_stream.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/audio/audio_send_stream.cc)
- Transport-wide-CC feedback interval is between 50 and 250 ms (default 100 ms), sized so that feedback is about 5% of the bandwidth estimate. "TwccReport size at 50ms interval is 24 byte… at 250ms interval is 36 byte"; below a minimum rate it uses the 250 ms max interval — [transport_sequence_number_feedback_generator.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/remote_bitrate_estimator/transport_sequence_number_feedback_generator.cc)
- Header extensions can be negotiated per transceiver via `setHeaderExtensionsToNegotiate` (W3C webrtc-extensions; also in native `RtpTransceiverInterface`) or removed by editing SDP `a=extmap` lines — [W3C webrtc-extensions](https://w3c.github.io/webrtc-extensions/)

### Inferences
**Assumptions for the table:** Opus payload = bitrate × ptime / 8 (TOC included). IPv4 20 + UDP 8 + RTP 12 + extensions 12 B (`audio-level` + `transport-cc` = 2+3 B of elements in a 4 B header, padded to 8 → 12 B) + SRTP-GCM tag 16 B = **68 B/packet**. Variants: IPv6 (+20 B); TURN/UDP ChannelData (+4 B); "minimal" = IPv4, no extensions, HMAC-SHA1-80 (10 B tag) = **50 B/packet**. Continuous speech, no DTX, one direction, 1 KB = 1000 B. Computed with a script.

| Opus kbps | ptime | payload B | wire kbps (v4 default) | KB/min v4 | KB/min v6 | KB/min v4 + TURN/UDP | KB/min minimal |
|---|---|---|---|---|---|---|---|
| 6 | 20 | 15 | 33.2 | 249 | 309 | 261 | 195 |
| 6 | 60 | 45 | 15.1 | 113 | 133 | 117 | 95 |
| 6 | 120 | 90 | 10.5 | 79 | 89 | 81 | 70 |
| 9 | 60 | 68 | 18.1 | 136 | 156 | 140 | 118 |
| 12 | 20 | 30 | 39.2 | 294 | 354 | 306 | 240 |
| 12 | 40 | 60 | 25.6 | 192 | 222 | 198 | 165 |
| 12 | 60 | 90 | 21.1 | 158 | 178 | 162 | 140 |
| 12 | 120 | 180 | 16.5 | 124 | 134 | 126 | 115 |
| 16 | 40 | 80 | 29.6 | 222 | 252 | 228 | 195 |
| 16 | 60 | 120 | 25.1 | 188 | 208 | 192 | 170 |
| 20 | 20 | 50 | 47.2 | 354 | 414 | 366 | 300 |
| 20 | 40 | 100 | 33.6 | 252 | 282 | 258 | 225 |
| 24 | 20 | 60 | 51.2 | 384 | 444 | 396 | 330 |
| 24 | 40 | 120 | 37.6 | 282 | 312 | 288 | 255 |
| 32 | 20 | 80 | 59.2 | 444 | 504 | 456 | 390 |
| 32 | 40 | 160 | 45.6 | 342 | 372 | 348 | 315 |
| 48 | 20 | 120 | 75.2 | 564 | 624 | 576 | 510 |
| 64 | 20 | 160 | 91.2 | 684 | 744 | 696 | 630 |

- **TURN over TCP/TLS** (IPv4, TCP header 32 B with timestamps option, ChannelData 4 B + padding, TLS 1.3 +22 B/record, one RTP packet per record; TCP ACKs on the reverse path excluded):

| Opus | ptime | Direct UDP | TURN/TCP | TURN/TLS |
|---|---|---|---|---|
| 6k | 20 ms | 33.2 kbps | 44.8 kbps | 53.6 kbps |
| 6k | 60 ms | 15.1 kbps | 19.2 kbps | 22.1 kbps |
| 12k | 20 ms | 39.2 kbps | 51.2 kbps | 60.0 kbps |
| 12k | 60 ms | 21.1 kbps | 25.1 kbps | 28.0 kbps |
| 24k | 20 ms | 51.2 kbps | 62.4 kbps | 71.2 kbps |
| 32k | 20 ms | 59.2 kbps | 70.4 kbps | 79.2 kbps |

  TCP/TLS fallback costs +25–60% at low rates. It also adds head-of-line blocking, so long ptime matters even more there.
- **DTX** (68 B header + ~3 B payload, one refresh packet per 400 ms ≈ 178 B/s ≈ 1.4 kbps during silence). With 50% talk time: 12k@20 ms ≈152 KB/min, 12k@60 ms ≈84 KB/min, 24k@20 ms ≈197 KB/min.
- **TWCC feedback cost** (IPv4/UDP + ~36 B report + SRTCP 4 B index + 16 B tag ≈ 84 B): 2.7 kbps (≈20 KB/min) at the 250 ms interval, 6.7 kbps at 100 ms and 13.4 kbps at 50 ms. In an audio-only Ultra-low call that is 15–20% or more on top of a ~15 kbps stream, sent by each receiver. Plain RTCP SR/RR (~80–100 B per report) adds well under 1 kbps.
- **Trimming:**
  - Drop `abs-send-time` (redundant with transport-cc): −4 B/packet when it is present. It is not in the 68 B baseline.
  - Drop `audio-level` unless the SFU needs it: −4 B/packet after padding.
  - For audio-only Ultra-low, consider negotiating **no** transport-cc. That saves the 4–8 B extension and all TWCC feedback, and relies on loss/RTT-based adaptation from RTCP RR plus app-level mode switching. Validate libwebrtc behaviour first (field trial `WebRTC-Audio-ABWENoTWCC` exists).
  - Keep GCM: the 6 B saving from HMAC-80 is not worth weakening the crypto default. AES-CM-HMAC-SHA1-32 (4 B tag) is disabled by default and not recommended.
  - Prefer IPv4 when both are available on cellular (−20 B/packet). This is an ICE candidate-priority policy choice.
- **Recommended on-wire budget per mode** (one direction, active speech, IPv4 direct UDP, 2 extensions, + ~2.7 kbps TWCC if kept):
  - Ultra-low ≈ 15–21 kbps @60 ms (≈110–160 KB/min), or 10.5–16.5 kbps @120 ms.
  - Data saver ≈ 25–34 kbps.
  - Balanced ≈ 38–59 kbps.
  - High ≈ 59–91 kbps.
  - Per device in a 1:1 call, double these figures (up + down).

### Gaps
- I did not confirm libwebrtc's audio RTCP report interval from code. `rtcp_report_interval_ms` is plumbed through from config, and the default was not located.
- I did not verify whether libwebrtc stops sending the `mid` extension after the first packets. If it does not, it adds 4+ B/packet.
- The TWCC report byte counts in libwebrtc's comment are approximate. Actual audio-only feedback rate depends on the receive-side estimate. Measure with packet captures.

---

## 3. Changing ptime / bitrate / DTX / FEC in native libwebrtc: SDP fmtp vs RTCRtpSender.setParameters

### Takeaway
`maxBitrate` (`encodings[0].max_bitrate_bps`), `adaptive_ptime` and the per-encoding codec can be changed live with `RtpSender::SetParameters`, without renegotiation. `ptime`/`maxptime`/`minptime`, `useinbandfec`, `usedtx`, `cbr`, `stereo` and `maxaveragebitrate` are Opus fmtp parameters. They come from the **remote** description, so changing them needs an offer/answer (or SDP munging). Because both ends are your own app, the cleanest approach is to put mode settings into SDP at call setup and use SetParameters for in-call changes.

### Cited Findings
- `SdpToConfig` in libwebrtc reads, from the negotiated Opus format: `useinbandfec=="1"` (FEC), `usedtx=="1"` (DTX), `cbr=="1"`, `maxaveragebitrate` (clamped 6k–510k), `maxplaybackrate`, `ptime` (frame size), and `minptime`/`maxptime` (used only with ANA). libwebrtc's default Opus fmtp is `minptime=10;useinbandfec=1` — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)
- RFC 7587: `ptime`/`maxptime` "are unidirectional receive-only parameters". `maxaveragebitrate` is "the maximum average receive bitrate". `useinbandfec` "specifies that the decoder has the capability"; "cbr … the maximum average bitrate can still change" — [RFC 7587](https://www.rfc-editor.org/rfc/rfc7587.txt)
- `WebRtcAudioSendStream::SetRtpParameters` detects changes to `encodings[0].max_bitrate_bps` and `adaptive_ptime` and reconfigures in place. "`rtp_max_bitrate_bps` is the bitrate from RtpSender::SetParameters". The send bitrate is `MinPositive(max_send_bitrate_bps, rtp_max_bitrate_bps)`, then checked against the codec min (a warning is logged if below) and max — [media/engine/webrtc_voice_engine.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/media/engine/webrtc_voice_engine.cc)
- `RtpEncodingParameters` has `bool adaptive_ptime` ("Allow dynamic frame length changes for audio", per w3c webrtc-extensions) and `std::optional<RtpCodec> codec` ("Allow changing the used codec for this encoding") — [api/rtp_parameters.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/rtp_parameters.h)
- The encoder exposes runtime setters: `SetFec`, `SetDtx`, `SetApplication`, `SetMaxPlaybackRate`, `SetFrameLength`, `SetReceiverFrameLengthRange`, `OnReceivedUplinkPacketLossFraction`, `OnReceivedTargetAudioBitrate`. These are internal (ANA/AudioEncoder API) and not exposed through the PeerConnection API — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)
- Field trial `WebRTC-Audio-OpusBitrateMultipliers` scales the target bitrate per kbps bucket (5 kbps and up) — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc)

### Inferences
- **Practical recipe (Swift, using the native ObjC wrapper `RTCRtpSender.parameters`):**
  1. At setup, the *answerer/receiver* writes the desired fmtp into its SDP, e.g. `a=fmtp:111 minptime=10;ptime=60;maxptime=120;useinbandfec=1;usedtx=1;maxaveragebitrate=12000;stereo=0;sprop-stereo=0`. The answerer's fmtp governs what the offerer sends, and vice versa. Both sides are your app, so munge both.
  2. During the call, move between modes by setting `encodings[0].maxBitrateBps`. This takes effect immediately, but it caps rather than sets: GCC/allocation can still go lower.
  3. Changing ptime/DTX/FEC needs a renegotiation (createOffer → setLocal → signal → setRemote). That recreates the encoder but needs no ICE restart. Alternatively, enable `adaptive_ptime` and let ANA choose the frame length.
- `maxaveragebitrate` is a hard ceiling. Use it as the per-mode upper bound and `maxBitrate` for fast in-call steps.
- A data-mode switch can be signalled over your Go signaling channel and applied by both peers, so each side re-munges its receive fmtp.

### Gaps
- I did not verify from code whether libwebrtc maps the SDP `a=ptime` attribute line into the Opus codec's `ptime` parameter (widely assumed). Test with both forms.
- I did not verify whether `maxBitrate` on audio includes or excludes packet overhead in current libwebrtc. The code path suggests it caps the codec target, but the overhead accounting in AudioSendStream may affect allocation.

---

## 4. Congestion control (GCC, transport-cc) and the Opus Audio Network Adaptor (ANA) in native builds

### Takeaway
ANA exists in native libwebrtc and can be driven two ways. The first is `RtpEncodingParameters.adaptive_ptime` (a built-in frame-length + bitrate controller, but with a hard-coded 16 kbps payload floor). The second is a custom protobuf ANA config through `AudioOptions` (`audio_network_adaptor` + `audio_network_adaptor_config`), which requires `WEBRTC_ENABLE_PROTOBUF`. Both are usable from a custom native build. Audio bandwidth estimation uses the transport-cc-based GCC; the TWCC feedback cost is significant at audio-only rates (§2).

### Cited Findings
- `AdaptivePtimeConfig` (field trial `WebRTC-Audio-AdaptivePtime`, or `encodings[0].adaptive_ptime=true`):
  - `min_payload_bitrate = 16 kbps`.
  - `min_encoder_bitrate = 16 kbps` ("chosen to ensure FEC can be encoded, see LBRR_WB_MIN_RATE_BPS in libopus").
  - `use_slow_adaptation = true`.
  - It builds an ANA `ControllerManager` with `frame_length_controller_v2` and `bitrate_controller`. With adaptive_ptime on, the stream `min_bitrate_bps` is lowered to `min_encoder_bitrate`.
  - — [webrtc_voice_engine.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/media/engine/webrtc_voice_engine.cc)
- ANA "only when `options_.audio_network_adaptor` equals true and `options_.audio_network_adaptor_config` has a value". These are fields of `AudioOptions` — [webrtc_voice_engine.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/media/engine/webrtc_voice_engine.cc), [api/audio_options.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_options.h)
- The Opus encoder includes `audio_network_adaptor/controller_manager.h`. `EnableAudioNetworkAdaptor(config)` creates the ANA. It receives uplink packet-loss fraction, target audio bitrate (from `BitrateAllocationUpdate`) and overhead. `uplink_bandwidth_update_interval_ms = 200` — [audio_encoder_opus.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_coding/codecs/opus/audio_encoder_opus.cc), [audio_encoder_opus_config.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio_codecs/opus/audio_encoder_opus_config.h)
- Audio send-stream field trials present in source include `WebRTC-Audio-ABWENoTWCC`, `WebRTC-Audio-AlrProbing`, `WebRTC-Audio-LegacyOverhead` and `WebRTC-Audio-PriorityBitrate`, plus a min/max allocation parser. If min/max bitrates are set and DSCP is not, overhead is included in the paced sender — [audio/audio_send_stream.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/audio/audio_send_stream.cc)
- TWCC feedback runs at 50–250 ms intervals, about 5% of the estimate — [transport_sequence_number_feedback_generator.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/remote_bitrate_estimator/transport_sequence_number_feedback_generator.cc)

### Inferences
- **For your modes:** stock `adaptive_ptime` will never let the payload go below 16 kbps / choose long ptime below that, so it suits **Data saver/Balanced** but not **Ultra-low**. For Ultra-low, either pin ptime=60/120 via SDP, or supply a custom ANA protobuf: a FrameLengthController(V2) with a lower `min_payload_bitrate_bps` (e.g. 6000), a BitrateController, and optionally FecController/DtxController.
- Make the app-level "data mode" a **ceiling** that GCC can only go under. Set `maxaveragebitrate`/`maxBitrate` per mode and let GCC/ANA adapt inside it. Under persistent loss/RTT, the app can step down a mode, which is a renegotiation for ptime changes.
- In Ultra-low audio-only mode, the trade-off of dropping transport-cc (≈2.7+ kbps saved) against losing delay-based BWE probably favours dropping it, because the codec bitrate is already pinned near the floor. This is an inference; test on emulated cellular links.

### Gaps
- I did not inspect the exact ANA protobuf schema (`modules/audio_coding/audio_network_adaptor/config.proto`) or the default FEC/DTX controller thresholds.
- I did not verify the exact semantics of `WebRTC-Audio-ABWENoTWCC`.
- The libwebrtc version currently shipped in common iOS binary distributions (e.g. the Google/LiveKit/stasel `WebRTC.xcframework` builds) was not checked for `WEBRTC_ENABLE_PROTOBUF` or the 120 ms ptime flag.

---

## 5. Alternative / neural low-bitrate codecs (Lyra v2, SoundStream, EnCodec, Satin, MLow, Opus "next") — worth it vs Opus 1.5/1.6 in 2026?

### Takeaway
The only open, standards-track, WebRTC-compatible path in 2026 is Opus 1.5/1.6 plus the IETF mlcodec extensions (DRED, speech enhancement, BWE). These are bitstream-compatible with RFC 6716. The proprietary neural or low-rate codecs that matter in production (Meta MLow, Microsoft Satin) are not available to third parties. Lyra/EnCodec are research or legacy and unsuitable for production on older phones. Recommendation: stay on Opus and invest in a custom libopus 1.6 build.

### Cited Findings
- IETF mlcodec WG charter: improve Opus packet-loss robustness and low-bitrate speech/music quality. It "will only consider solutions that result in bitstreams that are forwards and backwards compatible with RFC6716". Active drafts:
  - draft-ietf-mlcodec-opus-dred-07 (Aug 2026)
  - draft-ietf-mlcodec-opus-speech-coding-enhancement-04 (Jul 2026, Buethe & Valin)
  - draft-ietf-mlcodec-opus-scalable-quality-extension-02 ("Opus HD", Jun 2026)
  - — [mlcodec WG](https://datatracker.ietf.org/wg/mlcodec/about/), [DRED draft](https://datatracker.ietf.org/doc/draft-ietf-mlcodec-opus-dred/), [speech enhancement draft](https://datatracker.ietf.org/doc/draft-ietf-mlcodec-opus-speech-coding-enhancement/), [Opus HD draft](https://datatracker.ietf.org/doc/draft-ietf-mlcodec-opus-scalable-quality-extension/)
- Meta MLow (announced Jun 2024): POLQA 3.9 against Opus 1.89 at 6 kbps, with 10% lower complexity than Opus. It allows "FEC at much lower bitrates". It was "fully launched… to all Instagram and Messenger calls and… actively rolling it out on WhatsApp". Meta says EnCodec "sounds very good at low bitrates" but is computationally heavy. MLow is proprietary — [Meta MLow](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)
- Reporting on MLow says it and Opus score similarly at 15–25 kbps, and MLow's advantage appears below that — [Tecnoblog (pt)](https://tecnoblog.net/noticias/whatsapp-promete-ligacoes-melhores-e-mais-estaveis-com-novo-codec-de-audio/)
- Microsoft Satin: introduced 2020 as Silk's successor. By Feb 2021 it was used for all two-way Teams/Skype calls, "as low as 7kbps". Proprietary — [Wikipedia: Satin](https://en.wikipedia.org/wiki/Satin_(codec)), [BlogGeek: Lyra, Satin and WebRTC](https://bloggeek.me/lyra-satin-webrtc-voice-codecs/)
- Google Lyra V2 is based on SoundStream and runs at 3.2/6/9.2 kbps — [Wikipedia: Lyra](https://en.wikipedia.org/wiki/Lyra_(codec))

### Inferences
- Lyra v2 or EnCodec would need a custom RTP payload, your own PLC/FEC/jitter-buffer integration in NetEq, and non-trivial CPU on older iPhones. MLow shows that classical-DSP codecs can beat Opus by a wide margin at 6 kbps. Opus 1.5's NoLACE closes part of that gap on the decoder side, but no public head-to-head (Opus+NoLACE vs MLow) was found.
- For the Ultra-low mode, Opus 1.6 at ~8–10 kbps WB with NoLACE + BWE at the decoder and 60–120 ms ptime is probably the best open option in 2026. A custom neural codec is not justified unless you need sub-6 kbps.

### Gaps
- I could not confirm Lyra's current repository status (archived or maintained). The GitHub API query returned nothing.
- I found no 2025–2026 independent benchmark comparing Opus 1.6 (+NoLACE/BWE), MLow, Satin and Lyra at 6–9 kbps.
- I found no public information about an "Opus 2"/next-gen codec beyond the mlcodec extensions.

---

## 6. Video at low data (100–300 kbps): codec, hardware support on iPhone, resolution/fps ladders, simulcast/SVC

### Takeaway
On iOS/macOS, H.264 via VideoToolbox hardware is the pragmatic default for 100–300 kbps 1:1 video because of battery and thermals. No Apple chip encodes AV1 in hardware as of Oct 2026; AV1 decode is hardware from A17 Pro/M3. libwebrtc's own tables put 320×180 at 30–200 kbps (150 target) and 480×270/640×360 at a 150 kbps minimum. That implies a ladder of roughly 180p@15 for 100 kbps, 270p@15 for 150–200 kbps and 360p@15–20 for 300 kbps. Simulcast/SVC is irrelevant for P2P 1:1; it matters only with an SFU.

### Cited Findings
- libwebrtc VP8/H.264 simulcast format table (max / target / min kbps) — [video/config/simulcast.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/video/config/simulcast.cc):
  - 640×360: 700 / 500 / 150
  - 480×270: 450 / 350 / 150
  - 320×180: 200 / 150 / 30
  - Below 320×180 the target and max interpolate toward 0, and the min stays at 30 kbps.
- The default max video bitrate is 600 kbps for ≤320×240 and 1700 kbps for ≤640×480 (`GetMaxDefaultVideoBitrateKbps`). SDP `x-google-min-bitrate` / `x-google-max-bitrate` / `x-google-start-bitrate` fmtp parameters exist — [encoder_stream_factory.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/video/config/encoder_stream_factory.cc), [webrtc_video_engine.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/media/engine/webrtc_video_engine.cc)
- Apple AV1 status: hardware decode on A17 Pro (iPhone 15 Pro), every iPhone 16 and 17, M3+ Macs and M4 iPad Pro; **no hardware AV1 encode on any Apple silicon through M5 Pro/Max (Mar 2026)**. Hardware AV1 encoders are "roughly comparable to SVT-AV1 preset 8" — [Fora Soft, AV1 in 2026 (Oct 2026)](https://www.forasoft.com/learn/video-encoding/articles/av1-state-2026). This is secondary. A LiveAPI page lists the A17 Pro as AV1-encode-capable, which conflicts; Fora Soft is newer and consistent with Apple's VideoToolbox decode-only threads — [Apple Dev Forums AV1 decode](https://developer.apple.com/forums/thread/739953), [LiveAPI](https://liveapi.com/blog/av1-codec/)

### Inferences
- **Codec choice:**
  - **H.264 (VideoToolbox HW, Constrained Baseline/High)**: lowest power, universal hardware decode, and good enough at 150–300 kbps with 15 fps.
  - **VP8 (software)**: standard libwebrtc fallback.
  - **VP9/AV1 software (libvpx / libaom-rtc)**: better compression at ≤200 kbps (commonly cited at 30–50% vs H.264), but CPU/battery heavy on phones. Consider them only for macOS, or for short calls on A17+ where the receiver hardware-decodes AV1.
  - Measure before committing. This is not verified for iPhone here.
- **Ladder suggestion for 1:1:**

| Total video budget | Resolution | Frame rate | Key frames | Use |
|---|---|---|---|---|
| ~100 kbps | 320×180 (or 240×320 portrait) | 10–15 fps | ≥ 4–10 s apart | — |
| ~150–200 kbps | 480×270 | 15 fps | — | — |
| ~300 kbps | 640×360 | 15–20 fps | — | — |
| <60 kbps | — | — | — | Auto-switch to audio-only, or a "slideshow" at ≤5 fps |

  Use `degradationPreference = maintainFramerate` for faces at high-fps budgets, `balanced` otherwise. Set `encodings[0].maxBitrate`, `maxFramerate` and `scaleResolutionDownBy` live via SetParameters.
- In Data-saver/Ultra-low modes, keep the audio budget protected (audio priority high, video low). Note that 30 kbps is libwebrtc's video floor.

### Gaps
- I found no Apple primary documentation confirming the absence of an AV1 VideoToolbox encoder on iPhone 17 (A19). The status rests on a secondary source.
- I found no measured VideoToolbox H.264 rate-control accuracy at <150 kbps (overshoot/undershoot), and no H.264 vs VP9/AV1 VMAF-at-150-kbps data for iPhone in 2026.
- HEVC (H.265) in WebRTC on Apple was not researched.

---

## 7. Measured real-world data use of WhatsApp/Signal/Telegram/Meet audio calls (low-data modes)

### Takeaway
There is very little rigorous, recent measurement. The best academic number is old: a 2017/2018 study measured WhatsApp voice at ~35.75 kbps including headers (≈268 KB/min one direction). Blog figures range from 250 to 740 KB/min and are inconsistent. No reliable published numbers were found for Signal, Telegram or Meet "low data" modes.

### Cited Findings
- Dantas, Exton & Le Gear, "Bandwidth performance analysis of mobile VoIP solutions" (ECEASST vol. 75, 2018). Measured throughput including headers: Skype 113.37 kb/s, Viber 56.62 kb/s, WhatsApp 35.75 kb/s. The study's own (vendor-affiliated) solution used less than 50% of WhatsApp's. The figures come via a vendor press release, so check them against the paper tables — [UL research repository](https://researchrepository.ul.ie/articles/conference_contribution/Bandwidth_performance_analysis_of_mobile_VoIP_solutions/19851973), [GlobeNewswire press release, 2017](https://www.globenewswire.com/news-release/2017/02/07/1126288/0/en/University-Research-Proves-One-Horizon-s-VoIP-Software-is-Significantly-More-Efficient-Than-the-World-s-Leading-VoIP-Applications.html)
- An in-situ Android study measured Skype, WhatsApp and IMO over LTE and found Skype had the highest data rate — [arXiv 2003.05208](https://arxiv.org/pdf/2003.05208)
- Android Authority found Hangouts, Skype and WhatsApp among the heaviest, with WhatsApp at ~2.3x KakaoTalk (article undated) — [Android Authority](https://www.androidauthority.com/voice-call-data-comparison-598541/)
- Blog/vendor estimates of WhatsApp calls range from ~250–300 KB/min to ~0.5–0.74 MB/min. They are low quality, unsourced and conflicting — [Holafly](https://esim.holafly.com/internet/how-much-data-does-whatsapp-use/), [Firsty](https://www.firsty.app/help/general/how-much-data-does-whatsapp-use), [Paynow](https://paynow.co.zw/blog/how-much-data-does-whatsapp-call-use/), [3roam calculator](https://3roam.com/whatsapp-data-and-bandwidth-usage-calculator/)
- Meta states that modern codecs compress calls to 25–30 kbps, and that its apps lower the codec bitrate automatically when the network deteriorates — [Meta MLow](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)

### Inferences
- Comparable targets: WhatsApp-class voice is ~35 kbps on the wire (2017 data, pre-MLow). Your Data-saver mode at 12–16 kbps codec with 40–60 ms ptime (≈21–30 kbps wire) would be competitive or better. Ultra-low at 60–120 ms (≈10–21 kbps wire) would be well below the published WhatsApp figure.
- Run your own pcap-based benchmark of WhatsApp ("Use less data for calls"), Signal and Telegram on the same emulated link. Published data is insufficient.

### Gaps
- I found no peer-reviewed 2023–2026 measurement of WhatsApp (post-MLow), Signal, Telegram or Google Meet audio data use, with or without low-data toggles.

---

## 8. Tools to test: network emulation and quality metrics

### Takeaway
Combine link emulation with objective speech-quality scoring and WebRTC stats:
- Link emulation: Apple's Network Link Conditioner on device/macOS, Linux `tc netem` on a Go/TURN test box, and Opus's generative loss model for realistic bursts.
- Speech-quality scoring: ViSQOL (open source) or POLQA (licensed).
- WebRTC stats: `getStats` concealment metrics.

### Cited Findings
- Opus 1.5 ships a realistic generative packet-loss simulator. Build with `--enable-lossgen`, run `opus_demo -sim-loss <pct>`, source in `dnn/lossgen.c`, trained to model burst loss (<10k parameters). Microsoft's PLC-Challenge real loss traces are also available — [Opus 1.5 demo](https://opus-codec.org/demo/opus-1.5/), [microsoft/PLC-Challenge](https://github.com/microsoft/PLC-Challenge)
- In WebRTC, measure the concealment ratio as `concealedSamples / totalSamplesReceived` from inbound-rtp stats. Field trial `WebRTCFakeNetworkReceiveLossPercent/20/` injects receive loss in libwebrtc — [webrtcHacks RED](https://webrtchacks.com/red-improving-audio-quality-with-redundancy/)
- The IETF mlcodec speech test battery (intelligibility confusion matrices, used for the Opus 1.6 DRED evaluation) is draft-lechler-mlcodec-test-battery — [draft-lechler-mlcodec-test-battery-02](https://datatracker.ietf.org/doc/html/draft-lechler-mlcodec-test-battery-02), [Opus 1.6 demo](https://opus-codec.org/demo/opus-1.6/)
- Meta reports codec quality with POLQA MOS — [Meta MLow](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec/)
- POLQA is ITU-T P.863 (licensed) — [ITU-T P.863](https://www.itu.int/rec/T-REC-P.863). PESQ is ITU-T P.862 (older, narrowband/wideband) — [ITU-T P.862](https://www.itu.int/rec/T-REC-P.862). ViSQOL is Google's open-source objective metric with speech and audio modes — [google/visqol](https://github.com/google/visqol). Linux netem provides delay, jitter, loss (including Gilbert-Elliott burst models), rate and reordering — [tc-netem(8)](https://man7.org/linux/man-pages/man8/tc-netem.8.html)

### Inferences
- **Suggested test matrix per mode:**
  - Links: 3G-like (~100–400 kbps, 150–300 ms RTT); "Very Bad Network" NLC; netem Gilbert-Elliott with 5/10/20% average loss and 2–8 packet bursts; 300–1000 ms outages.
  - Metrics: ViSQOL speech MOS (wideband mode), concealment ratio, one-way mouth-to-ear delay, and bytes/min from pcaps (count SRTP + SRTCP + TURN framing).
  - Compare: 20/60/120 ms ptime, FEC on/off, decoder complexity 0 vs 5 vs 7, and DRED on/off (if patched).
- Use the Go server (pion-based TURN/SFU, if used) to log per-call byte counters as ground truth for "data used" UI.

### Gaps
- I did not verify current Network Link Conditioner distribution/profile details for Xcode 17/18. NLC is distributed with Apple's "Additional Tools for Xcode"; check the current version.
- I found no published ViSQOL-to-POLQA mapping specific to Opus at 6–12 kbps.
