# Data use results (CI, simulated), 2026-10-28

Label: **CI, simulated**. Two simulated clients (headless CallLabMac processes) on one macOS CI runner, over loopback. These are not device results. The owner allowed call lab L11 to use them for `DataMode.estimatedMBPer10Minutes` (2026-10-29, docs/superpowers/plans/README.md). L10's real-phone runs remain the release gate before the App Store and may replace these numbers.

## Setup

| Field | Value |
|---|---|
| Date | 2026-10-28 |
| Protocol followed | call lab L10a, `tools/call-lab/ci/run_calls.sh --matrix estimates`, commit `04e5290` |
| CI run | https://github.com/drag0n-flyer/myApp/actions/runs/40753746613 (workflow `call-lab-calls`, `-f matrix=estimates`), result: success |
| Caller device (model, iOS version) | n/a: CallLabMac `--headless --role caller`, GitHub `macos-26` runner |
| Callee device (model, iOS version) | n/a: CallLabMac `--headless --role callee` on the same runner (`client_b` = `mac-headless`) |
| Network | loopback (`lo0`), profile `clean` (no dummynet shaping), path `direct` (host/host candidates) |
| Video | none sent: the runner has no camera, so `v_bytes_sent` stays 0 on `balanced` and `high` (audio only) |
| Call length | `LAB_SECONDS` = 300 per call |
| Stimulus | LibriSpeech test-clean, 50% active speech (`make_stimulus.py --seconds 300`), on the caller |
| Bytes counted | IP packet length of all UDP on `lo0` for the call, both directions, by `pcapbytes.py` |

## Results

`mb_per_10min` counts both directions (sent plus received), scaled to 10 minutes. `kbps_per_direction` is half of that, in kbps on the wire. Every run passed the L10a pass rules (first audio, stats header, no disconnect).

| Run | Preset | Path | Profile | mb_per_10min | kbps_per_direction | loss_pct | connect_ms | Valid? |
|---|---|---|---|---:|---:|---:|---:|---|
| 01 | ultraLow | direct | clean | 1.78 | 11.9 | 0.0 | 1139 | yes |
| 02 | dataSaver | direct | clean | 2.75 | 18.3 | 0.0 | 1055 | yes |
| 03 | balanced | direct | clean | 4.10 | 27.3 | 0.0 | 1060 | yes |
| 04 | high | direct | clean | 5.04 | 33.6 | 0.0 | 1049 | yes |
| 05 | ultraLow | direct | clean | 1.76 | 11.7 | 0.0 | 1088 | yes |
| 06 | dataSaver | direct | clean | 2.76 | 18.4 | 0.0 | 1184 | yes |
| 07 | balanced | direct | clean | 4.10 | 27.3 | 0.0 | 1127 | yes |
| 08 | high | direct | clean | 4.99 | 33.3 | 0.97 | 1164 | yes |
| 09 | ultraLow | direct | clean | 2.25 | 15.0 | 0.0 | 1241 | yes |
| 10 | dataSaver | direct | clean | 2.58 | 17.2 | 0.0 | 1065 | yes |
| 11 | balanced | direct | clean | 4.10 | 27.3 | 0.0 | 1118 | yes |
| 12 | high | direct | clean | 5.04 | 33.6 | 0.0 | 1088 | yes |

All columns: [2026-10-28-data-use-ci.csv](2026-10-28-data-use-ci.csv).

### Medians (3 runs per cell)

| Preset | Median mb_per_10min | Median kbps_per_direction | `estimatedMBPer10Minutes` (rounded up, minimum 1) |
|---|---:|---:|---:|
| ultraLow | 1.78 | 11.9 | 2 |
| dataSaver | 2.75 | 18.3 | 3 |
| balanced | 4.10 | 27.3 | 5 |
| high | 5.04 | 33.6 | 6 |

### Relayed path (for reference)

From the weekly matrix, one run each, clean, through coturn on the runner (run https://github.com/drag0n-flyer/myApp/actions/runs/40744177096): `ultraLow` 2.31 MB per 10 min (15.4 kbps per direction), `balanced` 4.26 MB per 10 min (28.4 kbps per direction).

## Notes

- Run 09 (`ultraLow`) used about 27% more than runs 01 and 05; run 08 (`high`) saw 0.97% loss on a clean network. Both are reported as measured; the medians are not affected by them.
- The stimulus is 50% active speech with DTX on, so these numbers are lower than GOAL.md's continuous-speech wire estimates.
- Speech and noise scores (ViSQOL, DNSMOS) were not measured: no virtual audio device records client B's output on the runner.
