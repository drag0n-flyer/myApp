# Protocol: noise suppression (Apple VPIO against RNNoise)

Goal: decide whether the "Enhanced" mode ships RNNoise. VPIO is Apple's built-in voice processing. RNNoise is a small open-source noise remover.

## Decision rule (from GOAL.md section 4)

**Enhanced ships RNNoise only if OVRL improves by 0.2 or more over VPIO on at least 3 of 5 noise categories, at 0.15 RTF or less on the A12.** Otherwise Enhanced is deferred.

In plain words: OVRL is DNSMOS's overall score. RTF (real-time factor) is processing time divided by audio time, so 0.15 means 1 second of sound takes 0.15 seconds of CPU time.

## What is measured

The noise bench (the NS bench screen in the iPhone Call Lab) plays 150 mixed files through the cable rig: 5 categories (fan, traffic, keyboard, wind, babble) x 10 clips x 3 noise levels (SNR 0, 5 and 10 dB; a lower number is noisier). It records three passes per run: `raw.wav` (no processing), `vpio.wav` (Apple VPIO), and `rnnoise.wav` (RNNoise).

Devices: the A12 iPhone and the current iPhone. Do the whole protocol on each.

## Before you start

1. Build the 150 files. Follow `tools/call-lab/corpus/` (fetch the corpus, then mix with `mix_noise.py`) and write down the folder you mixed them into.
   Expected: 150 WAV files, 10 per category per level.
2. Set up the scoring tools by following `tools/call-lab/scoring/README.md`.
   Expected: `python3 tools/call-lab/scoring/score.py dnsmos --in <any mixed file>` prints `{"sig": ..., "bak": ..., "ovrl": ...}`.
3. Wire the cable rig as in [README.md](README.md): Mac output into the iPhone's headset microphone input. Turn off Wi-Fi Assist, Bluetooth and Low Data Mode on the phone. Plug the iPhone into the Mac with a USB cable too (see the "Known problem" in [README.md](README.md)).
   Expected: the iPhone's NS bench screen shows input level moving when the Mac plays a sound.

## Steps (repeat for each iPhone)

1. Open the NS bench screen in Call Lab and load the playlist of the 150 files.
   Expected: the screen lists 150 files.
2. On the Mac, start the rig playing the playlist, then tap Start on the NS bench screen.
   Expected: the bench works through the files and shows progress, and finishes with a "done" state.
3. Export the run folder from the phone and unzip it into `tools/call-lab/runs/<run_id>/`.
   Expected: the folder holds `raw.wav`, `vpio.wav`, `rnnoise.wav` and `bench.json`.
4. Read `bench.json`. It holds one entry per mode (`raw`, `vpio`, `rnnoise`) with `realTimeFactor`, `cpuSeconds`, `footprintBytesBefore` and `footprintBytesAfter`.
   Expected: three entries.
5. For each mode record the footprint delta (memory added by loading the model): `footprintBytesAfter - footprintBytesBefore`.
   Expected: a number of bytes. Convert to MB (1 MB = 1 000 000 bytes) for the result.
6. Score each pass with DNSMOS. For each of `vpio.wav` and `rnnoise.wav`, and for `raw.wav` as the baseline, cut the recording into one clip per played file (the clips play in playlist order; if the bench folder has no clip markers, ask the project agent for a split script before continuing), then run for each clip:

   ```
   python3 tools/call-lab/scoring/score.py dnsmos --in <clip>.wav
   ```

   Expected: `{"sig": x, "bak": y, "ovrl": z}` for each clip, each between 1 and 5.
7. Score RNNoise against the clean reference with ViSQOL:

   ```
   python3 tools/call-lab/scoring/score.py visqol --ref <clean clip>.wav --deg <rnnoise clip>.wav
   ```

   Expected: `{"visqol_moslqo": x}`.
8. Average SIG, BAK and OVRL per mode and per noise category. Then compute, per category, `OVRL(rnnoise) - OVRL(vpio)`.
   Expected: a 5-row table (one per category) with a difference column.
9. Run the 30-minute bench loop for battery: charge to 80% or more, unplug, lock nothing (the bench must stay in front), start the NS bench loop in `rnnoise` mode for 30 minutes, then repeat with `vpio`. Read the battery % at start and end from the phone, and the `battery` column of the loop's `stats.csv` if present.
   Expected: two numbers per mode: start % and end %. Subtract to get the drop.

## Where each number comes from

| Metric | Source |
|---|---|
| DNSMOS SIG, BAK, OVRL | `score.py dnsmos` output (`sig`, `bak`, `ovrl`) |
| ViSQOL for `rnnoise` against the clean reference | `score.py visqol` output (`visqol_moslqo`) |
| `realTimeFactor` (pass: under 0.15 on the A12) | `bench.json` |
| Footprint delta | `bench.json` (`footprintBytesAfter` minus `footprintBytesBefore`) |
| Battery over the 30-minute bench loop | battery % read at start and end (phone screen, and `battery` column in `stats.csv`) |

## Decide

1. Count the categories where `OVRL(rnnoise) - OVRL(vpio)` is 0.2 or more.
   Expected: a number from 0 to 5.
2. Read `realTimeFactor` for `rnnoise` on the A12 phone.
   Expected: a number. It passes only if under 0.15.
3. Write exactly one line at the end of the result:
   - "Enhanced ships RNNoise" if at least 3 categories improve by 0.2 or more **and** the A12 `realTimeFactor` is under 0.15.
   - "Enhanced is deferred" in every other case.

## Finish

Fill in [templates/result.md](templates/result.md) as `docs/measurements/YYYY-MM-DD-noise.md`, with the category table, the RTF and footprint numbers per phone, the battery numbers, and the decision line.
