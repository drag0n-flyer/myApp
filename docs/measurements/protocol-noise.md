# Protocol: noise suppression (Apple VPIO against RNNoise)

Goal: decide whether the "Enhanced" mode ships RNNoise. VPIO is Apple's built-in voice processing. RNNoise is a small open-source noise remover.

## Decision rule (from GOAL.md section 4)

**Enhanced ships RNNoise only if OVRL improves by 0.2 or more over VPIO on at least 3 of 5 noise categories, at 0.15 RTF or less on the A12.** (The wording in this protocol: pass means `realTimeFactor` is 0.15 or less.) Otherwise Enhanced is deferred.

In plain words: OVRL is DNSMOS's overall score. RTF (real-time factor) is processing time divided by audio time, so 0.15 means 1 second of sound takes 0.15 seconds of CPU time.

## What is measured

The Mac rig plays 150 mixed files over the cable into the iPhone's headset microphone, and the noise bench (the NS bench screen in the iPhone Call Lab) records them. The set is 150 mixed files: 5 categories (fan, traffic, keyboard, wind, babble) x 10 clips x 3 noise levels (SNR 0, 5 and 10 dB; a lower number is noisier). It records three passes per run, each in its own folder: `raw/` (no processing), `vpio/` (Apple VPIO), and `rnnoise/` (RNNoise).

Devices: the A12 iPhone and the current iPhone. Do the whole protocol on each.

## Before you start

1. Build the 150 files. Follow `tools/call-lab/corpus/` (fetch the corpus, then mix with `mix_noise.py`) and write down the folder you mixed them into.
   Expected: 150 WAV files, 10 per category per level.
2. Set up the scoring tools by following `tools/call-lab/scoring/README.md`.
   Expected: `python3 tools/call-lab/scoring/score.py dnsmos --in <any mixed file>` prints `{"sig": ..., "bak": ..., "ovrl": ...}`.
3. Wire the cable rig as in [README.md](README.md): Mac output into the iPhone's headset microphone input. Turn off Wi-Fi Assist, Bluetooth and Low Data Mode on the phone.
   Expected: the iPhone's NS bench screen shows input level moving when the Mac plays a sound.

## Steps (repeat for each iPhone)

1. Open the NS bench screen in Call Lab and load the playlist of the 150 files.
   Expected: the screen lists 150 files.
2. On the Mac, start the rig playing the playlist, then tap Start on the NS bench screen.
   Expected: the bench works through the files and shows progress, and finishes with a "done" state.
3. Export the run folder from the phone and unzip it into `tools/call-lab/runs/<run_id>/`.
   Expected: the folder holds `raw/`, `vpio/` and `rnnoise/` (one WAV per clip in each) and `bench.json`.
4. Read `bench.json`. It holds one entry per mode (`raw`, `vpio`, `rnnoise`) with `realTimeFactor`, `cpuSeconds`, `footprintBytesBefore` and `footprintBytesAfter`.
   Expected: three entries.
5. For each mode record the footprint delta (memory added by loading the model): `footprintBytesAfter - footprintBytesBefore`.
   Expected: a number of bytes. Convert to MB (1 MB = 1 000 000 bytes) for the result.
6. Score the clips. After the run, the bench (Task L5) will write one WAV file per clip for each pass, in one subfolder per mode: `raw/<clip-id>.wav`, `vpio/<clip-id>.wav` and `rnnoise/<clip-id>.wav`, next to `bench.json`. Score each mode folder with one command (available after L8 scoring is approved; the L8 scoring tool will provide folder mode):

   ```
   python3 tools/call-lab/scoring/score.py dnsmos --in tools/call-lab/runs/<run_id>/vpio/
   ```

   Run it again with `raw/` and `rnnoise/`.
   Expected: one `{"sig": x, "bak": y, "ovrl": z}` per clip, each value between 1 and 5, for 150 clips in each of the three folders.
7. Score each mode folder against the clean references with ViSQOL (available after L8 scoring is approved). The clean references are the clean speech clips you gave to `mix_noise.py --clean` in "Before you start" step 1; keep them in one folder, named by the same `<clip-id>`, here called `<clean-folder>`:

   ```
   python3 tools/call-lab/scoring/score.py visqol --ref <clean-folder> --deg tools/call-lab/runs/<run_id>/rnnoise/
   ```

   Run it again with `vpio/` for comparison.
   Expected: a `{"visqol_moslqo": x}` per clip, matched by `<clip-id>`.
8. Average SIG, BAK and OVRL per mode and per noise category. Then compute, per category, `OVRL(rnnoise) - OVRL(vpio)`.
   Expected: a 5-row table (one per category) with a difference column.
9. Run the 30-minute bench loop for battery: charge to 80% or more, unplug, lock nothing (the bench must stay in front), start the NS bench loop in `rnnoise` mode for 30 minutes, then repeat with `vpio`. Read the battery % at start and end from the phone, and the `battery` column of the loop's `stats.csv` if present.
   Expected: two numbers per mode: start % and end %. Subtract to get the drop.

## Where each number comes from

| Metric | Source |
|---|---|
| DNSMOS SIG, BAK, OVRL | `score.py dnsmos` output (`sig`, `bak`, `ovrl`) |
| ViSQOL for `rnnoise` against the clean reference | `score.py visqol` output (`visqol_moslqo`) |
| `realTimeFactor` (pass: 0.15 or less on the A12) | `bench.json` |
| Footprint delta | `bench.json` (`footprintBytesAfter` minus `footprintBytesBefore`) |
| Battery over the 30-minute bench loop | battery % read at start and end (phone screen, and `battery` column in `stats.csv`) |

## Decide

1. Count the categories where `OVRL(rnnoise) - OVRL(vpio)` is 0.2 or more.
   Expected: a number from 0 to 5.
2. Read `realTimeFactor` for `rnnoise` on the A12 phone.
   Expected: a number. It passes only if 0.15 or less.
3. Write exactly one line at the end of the result:
   - "Enhanced ships RNNoise" if at least 3 categories improve by 0.2 or more **and** the A12 `realTimeFactor` is 0.15 or less.
   - "Enhanced is deferred" in every other case.

## Finish

Fill in [templates/result.md](templates/result.md) as `docs/measurements/YYYY-MM-DD-noise.md`, with the category table, the RTF and footprint numbers per phone, the battery numbers, and the decision line.
