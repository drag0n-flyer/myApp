# Phase 0 measurements

This folder holds the protocols you follow on real devices, and the results. Each protocol is a numbered checklist. Every step has an **Expected** line that says what "done" looks like. If what you see differs from Expected, stop and fix that step before going on.

## Index

| Protocol | What it answers | Result file you create |
|---|---|---|
| [protocol-data-use.md](protocol-data-use.md) | How many bytes does a call use per preset? | `YYYY-MM-DD-data-use.md` and `.csv` |
| [protocol-comparison.md](protocol-comparison.md) | How do we compare to WhatsApp, Signal and Telegram? | `YYYY-MM-DD-comparison.md` |
| [protocol-noise.md](protocol-noise.md) | Does RNNoise beat Apple's built-in noise removal? | `YYYY-MM-DD-noise.md` |
| [protocol-battery.md](protocol-battery.md) | How much battery does a 30-minute call use? | `YYYY-MM-DD-battery.md` |
| [protocol-reliability.md](protocol-reliability.md) | Do calls connect, how fast, and do they survive Wi-Fi to cellular? | `YYYY-MM-DD-reliability.md` |

Supporting files:

- [network-profiles.md](network-profiles.md): the bad-network profiles (`clean`, `loss5`, `cap32`, ...) and how to set them.
- [templates/result.md](templates/result.md): copy this for every result file.
- [templates/data-use.csv](templates/data-use.csv): copy this for the data-use table.

Only summary results (Markdown and CSV, each under 1 MB) are committed here. Captures (`.pcap`), recordings (`.wav`) and run folders stay in `tools/call-lab/runs/`, which git ignores.

## Definitions

These are fixed. Use them exactly.

- **The lab never ships.** Its bundle ID is `org.example.app.calllab`. It is installed via Xcode or internal TestFlight only. Plaintext SDP (the text a call uses to agree on audio settings) is allowed here and nowhere else.
- **Bytes on the wire** means the IP packet length: the IP header, the UDP or TCP header, and the payload. So the encryption (SRTP), RTCP, STUN and TURN overhead is included, and link-layer framing is not. It is always counted on one call device (through the Mac's Internet Sharing capture, or `rvictl`), in both directions, from the first to the last media packet.
- **Units:** 1 kbps = 1 000 bit/s. 1 MB = 1 000 000 bytes. This matches AppCore `DataFormat`.
- **Path:** `direct` means the two phones talk to each other. `relay` means the call is forced through the TURN relay server (`iceTransportPolicy = .relay`).
- **Presets** (from GOAL.md section 4):

  | Preset | Opus bitrate (bit/s) | ptime (ms) | DTX | FEC | Playback (Hz) | Video |
  |---|---:|---:|---|---|---:|---|
  | `ultraLow` | 9 000 | 60 | on | off | 16 000 | off |
  | `dataSaver` | 15 000 | 60 | on | on | 16 000 | off |
  | `balanced` | 24 000 | 20 | on | on | 48 000 | 270p15 at 175 kbps |
  | `high` | 40 000 | 20 | on | on | 48 000 | 360p20 at 300 kbps |

  In plain words: Opus is the audio format. ptime is how much sound goes in each packet. DTX stops sending packets while nobody talks. FEC adds a little extra data so lost packets can be repaired. Video rungs: `p180_15` at 100 kbps, `p270_15` at 175 kbps, `p360_20` at 300 kbps.
- **Speech stimulus:** one fixed 5-minute "conversation" WAV (`stimulus.wav`), 50% speech (plus or minus 2%), with 4 to 8 second talk spans and silences. It is built from LibriSpeech test-clean. It is played into the caller's microphone over a cable, so every app and every run hears identical input and DTX savings are comparable.
- **Network emulation:** relayed calls use Linux `tc netem` on the coturn host. Direct calls, and other apps, use the iOS Network Link Conditioner (NLC) with identical profiles on both iPhones. See [network-profiles.md](network-profiles.md).
- **Quality metrics** (offline, on the Mac):
  - **ViSQOL v3 speech mode** compares what the callee heard with the clean stimulus. Higher is better (about 1 to 5).
  - **DNSMOS P.835** gives three non-intrusive scores for noise removal: SIG (speech), BAK (background), OVRL (overall). Higher is better.
  - Not PESQ or POLQA: they need a paid license.
- **Noise-suppression comparison:** Apple VPIO (the built-in voice processing) against RNNoise, on the oldest supported iOS 17 iPhone (an A12: iPhone XS, XS Max or XR) and one current iPhone.
- **Battery:** each data point is a 30-minute audio call with the screen locked, at 80% charge or more at the start, not charging, on Wi-Fi with Bluetooth off, at about 20 degrees C room temperature. The lab logs battery level and thermal state every 60 s. Xcode Instruments Energy Log is the second source. Each configuration gets 3 runs.

## Where every number comes from

Each protocol names its metrics. Each comes from exactly one of these sources:

| Source | What it is | Where |
|---|---|---|
| `stats.csv` | One row per second from the lab app (`StatsRow` columns, such as `a_pkts_sent`, `concealed_samples`, `total_samples_recv`, `local_cand`, `remote_cand`, `thermal`, `battery`) | `runs/<run_id>/stats.csv` |
| `events.csv` | Timed events: `invite_sent`, `ring_received`, `answer_sent`, `answer_received`, `ice_connected`, `first_audio_packet`, `disconnected`, `restart_sent`, `reconnected`, `ended` | `runs/<run_id>/events.csv` |
| `pcapbytes` output | JSON with `duration_s`, `up_bytes`, `down_bytes`, `bytes_per_min`, `mb_per_10min`, ... | `tools/call-lab/pcap/pcapbytes.py` |
| `bench.json` | Noise bench results (`realTimeFactor`, footprint) | `runs/<run_id>/bench.json` |
| `score.py` output | ViSQOL (`visqol_moslqo`) and DNSMOS (`sig`, `bak`, `ovrl`) | `tools/call-lab/scoring/score.py` |

`runs/<run_id>/` is a folder the lab app writes. The `run_id` looks like `20261105-141500-balanced-direct`.

## Devices

You need three devices:

1. One A12 iPhone (iPhone XS, XS Max or XR) on iOS 17.x. This is the oldest supported phone.
2. One current iPhone on the newest iOS.
3. One Apple-silicon Mac on macOS 14 or later.

Both iPhones need Developer Mode on (Settings, Privacy and Security, Developer Mode), the Call Lab app installed, and the Network Link Conditioner profiles added. The Mac needs Xcode. Write the exact device models, iOS versions, and the lab build number in every result.

You also need the lab server running with `APP_LAB_MODE=1`, coturn up, and a lab session token on each device (from `appserver lab session`).

## Hardware for the cable rig

The rig feeds the same audio into the phone every time and records what comes out, with no room noise. You need:

- A USB audio interface with a line input and a line output (about US$100).
- Lightning or USB-C headphone adapters (to match each iPhone).
- TRRS splitter cables (they join the headset's microphone and headphone connections into one plug).
- Cables to join the interface to those splitters.

Wiring:

- Mac audio output goes through the TRRS adapter into the caller iPhone's headset microphone input.
- The callee iPhone's headphone output goes into the Mac's input.

If you cannot buy this, the fallback is a speaker and a microphone in a quiet room. It is less repeatable, so write "acoustic fallback" in the result.

Capture note: the phone's single port is taken by the headset adapter, so the Mac captures the phone's traffic through Internet Sharing (the Mac becomes the Wi-Fi network the phones join, and `tcpdump -i bridge100` records it) instead of `rvictl`. The `rvictl` method (USB, Mac-side virtual interface) is only an alternative for runs without the adapter. Setup is in [protocol-data-use.md](protocol-data-use.md).

## CI, simulated

Workflow `.github/workflows/call-lab-calls.yml` (job `calls`, one `macos-26` runner, weekly on Monday 03:00 UTC and by hand, never on push) places test calls between two simulated clients. Its results are labelled **CI, simulated**. They are not device results: they never go into the L10 tables, GOAL.md's mode table or `DataMode.estimatedMBPer10Minutes` unless the owner says so. L10 stays the release gate.

How a run works (`tools/call-lab/ci/run_calls.sh`):

- The lab server runs on the runner with `APP_LAB_MODE=1`, with a local coturn. Tokens come from `appserver lab session`.
- Client A and client B are two `CallLabMac` processes started with `--headless` (client B with `--role callee`). The iOS Simulator is not used; each result says `client_b: mac-headless`.
- Client A's microphone samples are replaced with the L8 stimulus (LibriSpeech test-clean, 50% active) through the WebRTC capture hook (patch 0004).
- `tools/call-lab/netem/dummynet.sh` applies the `profiles.tsv` profile on `lo0` with `dnctl` and `pfctl`. Each UDP packet passes exactly one pipe: only the outbound pass on `lo0` is shaped, and a mask gives each sending socket (each call direction) its own pipe. On relay, only the clients' sends to coturn are shaped, so each direction is shaped once. Signaling (TCP) is not shaped.
- After every shaped call (not `clean`), pf's counter on the anchor's dummynet rule must be above 0, or the job fails. `dnctl pipe show` is not used for this: its per-queue rows are garbage on macOS 26.
- Data use: `mb_per_10min` counts client A's sent plus received bytes; `kbps_per_direction` is per direction, the figure to compare with GOAL.md's per-direction wire budget. Relay runs must have selected a relay candidate pair.
- Jitter: **not simulated**. dummynet has no jitter, so the profiles' `jitter_ms` is not applied, and the summary's `jitter` column says "not simulated".
- `tcpdump` on `lo0` feeds `pcapbytes.py`. Data use is client A's own traffic. On direct, that is the whole A ↔ B capture (each packet once). On relay, it is A's own leg to coturn, picked out by A's UDP ports; B's leg is reported separately (`callee_mb_per_10min`).
- `tools/call-lab/ci/summarize.py` writes `summary.md` and `summary.csv` (the workflow artifact).

Matrix: 7 calls of 5 minutes. The full matrix (2 presets × 2 paths × 4 profiles plus 3 noise runs, 19 calls) does not fit the 60-minute job, so it was cut, not the cap:

| Preset | Path | Profile |
|---|---|---|
| ultraLow | direct | clean |
| balanced | direct | clean |
| ultraLow | relay | clean |
| balanced | relay | clean |
| ultraLow | direct | loss5 |
| balanced | direct | cap32 |
| balanced | direct | 3g |

Pass rules (the only gates): every call has `first_audio_packet`; `stats.csv` has exactly `StatsRow.csvHeader`; on profile `clean`, no `disconnected` event and `answer_received` → `first_audio_packet` at or below 3 seconds (GOAL.md §9). Speech quality, noise quality, bitrate and data use, packet loss and latency are report only.

Not measured here (compare with real devices): battery, thermal state and CPU on an A-series chip; real radio (cellular, Wi-Fi roaming, handover, carrier NAT, Bluetooth); CallKit and PushKit, the lock screen, audio-session interruptions, real microphone and speaker acoustics, echo; VPIO and the RNNoise device cost. dummynet on `lo0` is symmetric loopback shaping, not a real network. ViSQOL and DNSMOS are reported as "not measured" until client B's output can be recorded on the runner (a virtual audio device needs the owner's approval).

## Phase 0 exit checklist

Tick each box only when the result file is committed. (The push-notification prototype is a separate plan.)

- [ ] Repeatable one-to-one audio-call prototype: Call Lab smoke checklist passed on both paths (`direct` and `relay`).
- [ ] Measured data use per preset: [data-use](protocol-data-use.md), 3 valid runs in every cell.
- [ ] Voice quality: ViSQOL per preset and profile, and the comparison with three apps ([data-use](protocol-data-use.md), [comparison](protocol-comparison.md)).
- [ ] Connection reliability: setup success, setup time, handover recovery from 100 attempts ([reliability](protocol-reliability.md)).
- [ ] CPU: `realTimeFactor` for RNNoise ([noise](protocol-noise.md)), and Instruments CPU for calls per preset ([battery](protocol-battery.md)).
- [ ] Energy: battery results on the A12 and the current iPhone ([battery](protocol-battery.md)).
- [ ] Noise-suppression decision: the "ship RNNoise" or "defer" line is written in the noise result ([noise](protocol-noise.md)).
