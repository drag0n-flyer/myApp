# Call lab scoring

Offline quality scores for recordings made with the lab: ViSQOL v3 (against a clean reference) and DNSMOS P.835 (no reference). Runs on a computer only. It is never part of the app.

## Set up

Needs Python 3.12 or 3.14. The ViSQOL package (`speequal`) ships ready-made wheels only for Linux x86_64 with those two Python versions. On a Mac, `pip` builds it from source, which needs Bazel and a TensorFlow Lite build. If that is a problem, run the scoring on a Linux x86_64 machine (copy the `runs/` folder over).

```
cd tools/call-lab/scoring
python3.12 -m venv .venv
.venv/bin/pip install --require-hashes -r requirements.txt
```

`requirements.txt` pins every package with SHA-256 hashes. `requirements.in` holds the three direct packages: `numpy`, `onnxruntime`, `speequal`. Never install them system-wide.

Fetch the DNSMOS model (1.1 MB). It is pinned by URL and SHA-256 in `../corpus/SOURCES.tsv`, and `score.py` refuses a file whose hash differs:

```
python3 tools/call-lab/corpus/fetch.py --only dnsmos-sig-bak-ovr
```

The file lands in `tools/call-lab/corpus/data/archives/sig_bak_ovr.onnx` and is not committed.

Licenses: ViSQOL (Google, Apache-2.0, repackaged by `speequal`); DNSMOS models and `microsoft/DNS-Challenge` repository content (CC BY 4.0, code MIT); `numpy` (BSD-3); `onnxruntime` (MIT). Cite the DNSMOS P.835 paper (Reddy, Gopal, Cutler, ICASSP 2022) when publishing results.

## Use

Input WAV files are mono 16-bit PCM at any sample rate (convert on a Mac with `afconvert -f WAVE -d LEI16@48000 -c 1 in out.wav`).

```
# One recording against its reference
.venv/bin/python score.py visqol --ref stimulus.wav --deg received.wav
{"visqol_moslqo": 3.91}

# A whole mode folder against the clean clips (matched by <clip-id>.wav)
.venv/bin/python score.py visqol --ref <clean-folder> --deg tools/call-lab/runs/<run_id>/rnnoise/
{"clip": "fan-01-snr5", "visqol_moslqo": 3.12}   (one line per clip)

# DNSMOS on one file, or on a folder
.venv/bin/python score.py dnsmos --in file.wav
{"sig": 3.67, "bak": 4.22, "ovrl": 3.46}
.venv/bin/python score.py dnsmos --in tools/call-lab/runs/<run_id>/vpio/
```

What it does:

- ViSQOL: both files go to 16 kHz, the recording is aligned to the reference by cross-correlation (lag searched within 2 seconds either way), both are cut to the same length, then ViSQOL speech mode scores them (MOS-LQO, 1 to 5, upstream default mapping).
- DNSMOS: 9.01-second windows hopped by 1 second (short clips are repeated), the average is mapped with the published polynomial fit for non-personalized scores. Values are limited to 1 to 5.
- Exit codes: 0 done, 1 some recordings had no clean reference (reported on stderr), 2 bad input, missing model or wrong hash.
- ViSQOL's identical-pair ceiling is about 4.5 to 4.6 on real speech. Synthetic signals score lower. Do not compare scores across different mappings.

## Tests

```
cd tools/call-lab/scoring
.venv/bin/python -m unittest -v
```

Tests that need `numpy`, `speequal`, `onnxruntime` or the DNSMOS model skip with a reason when those are missing. `test_visqol_identical_is_high` also needs one real speech clip: set `CALL_LAB_SPEECH_WAV=<mono 16-bit wav>` or put converted LibriSpeech clips in `tools/call-lab/corpus/data/speech48k/`.
