#!/usr/bin/env python3
"""Offline speech-quality scoring for the call lab. Needs the scoring venv (see README.md).

  score.py visqol --ref stimulus.wav --deg received.wav   -> {"visqol_moslqo": x}
  score.py visqol --ref <clean-folder> --deg <mode-folder> -> one JSON line per clip id
  score.py dnsmos --in file.wav                            -> {"sig": x, "bak": y, "ovrl": z}
  score.py dnsmos --in <folder>                            -> one JSON line per clip

ViSQOL v3 (speech mode, via the speequal wheel) compares a recording with its clean reference
after cross-correlation alignment (max lag 2 s) and resampling to 16 kHz. DNSMOS P.835 uses
Microsoft's sig_bak_ovr.onnx with the published non-personalized polynomial fit, as in
microsoft/DNS-Challenge DNSMOS/dnsmos_local.py. Input WAVs are mono 16-bit PCM at any rate.
Exit codes: 0 ok, 1 some clips failed or could not be paired (recording without reference, or
reference without recording; each failed clip also prints {"clip", "error"}), 2 bad input, model or hash.
"""
import argparse
import csv
import hashlib
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORPUS = HERE.parent / "corpus"
sys.path.insert(0, str(CORPUS))  # shared WAV reader (also correct under python -I)

import wavio  # noqa: E402

RATE = 16000
MAX_LAG_SECONDS = 2.0
MIN_SECONDS = 0.1
DNSMOS_SECONDS = 9.01
DNSMOS_SOURCES_NAME = "dnsmos-sig-bak-ovr"
DEFAULT_MODEL = CORPUS / "data" / "archives" / "sig_bak_ovr.onnx"
# microsoft/DNS-Challenge DNSMOS/dnsmos_local.py, is_personalized_MOS = False
_POLY_SIG = (-0.08397278, 1.22083953, 0.0052439)
_POLY_BAK = (-0.13166888, 1.60915514, -0.39604546)
_POLY_OVR = (-0.06766283, 1.11546468, 0.04602535)


def _np():
    try:
        import numpy
    except ImportError:
        sys.exit("numpy is not installed. Create the venv as described in scoring/README.md.")
    return numpy


# ---- audio helpers ------------------------------------------------------------------------

def load(path):
    """Return (float64 samples in [-1, 1), rate)."""
    np = _np()
    samples, rate = wavio.read_wav(path)
    if not samples:
        raise ValueError(f"{path}: the WAV file is empty (0 samples)")
    if len(samples) < MIN_SECONDS * rate:
        raise ValueError(f"{path}: the WAV file is too short ({len(samples) / rate:.3f} s, need at least {MIN_SECONDS} s)")
    return np.asarray(samples, dtype="float64") / 32768.0, rate


def resample(x, rate_in, rate_out):
    """Band-limited resampling in the frequency domain (brick-wall at the lower Nyquist)."""
    np = _np()
    if rate_in == rate_out:
        return x
    n_out = int(round(len(x) * rate_out / rate_in))
    spec = np.fft.rfft(x)
    m = n_out // 2 + 1
    if m <= len(spec):
        spec = spec[:m]
    else:
        spec = np.concatenate([spec, np.zeros(m - len(spec), dtype=spec.dtype)])
    return np.fft.irfft(spec, n_out) * (n_out / len(x))


def find_lag(ref, deg, rate, max_lag_s=MAX_LAG_SECONDS):
    """Lag in samples at which deg best matches ref (positive: deg is later than ref).

    Cross-correlation via FFT, searched over -max_lag_s..+max_lag_s.
    """
    np = _np()
    max_lag = int(round(max_lag_s * rate))
    n = 1 << (len(ref) + len(deg) - 1).bit_length()
    corr = np.fft.irfft(np.fft.rfft(deg, n) * np.conj(np.fft.rfft(ref, n)), n)
    lags = np.arange(-max_lag, max_lag + 1)
    return int(lags[int(np.argmax(corr[lags % n]))])


def align(ref, deg, lag):
    """Trim so ref[i] lines up with deg[i]; both end at the shorter length."""
    if lag >= 0:
        deg = deg[lag:]
    else:
        ref = ref[-lag:]
    n = min(len(ref), len(deg))
    return ref[:n], deg[:n]


# ---- ViSQOL -------------------------------------------------------------------------------

def visqol_score(ref, deg, rate):
    """MOS-LQO (speech mode) of two 16 kHz float arrays that are already aligned."""
    if rate != RATE:
        raise ValueError("ViSQOL speech mode needs 16 kHz input")
    try:
        from speequal import visqol
    except ImportError:
        sys.exit("speequal is not installed. Create the venv as described in scoring/README.md.")
    return float(visqol.speech(ref, deg, fs=RATE))


def visqol_files(ref_path, deg_path):
    ref, r_rate = load(ref_path)
    deg, d_rate = load(deg_path)
    ref, deg = resample(ref, r_rate, RATE), resample(deg, d_rate, RATE)
    lag = find_lag(ref, deg, RATE)
    ref, deg = align(ref, deg, lag)
    if len(ref) < RATE:
        raise ValueError(f"{deg_path}: too short, less than 1 s left after alignment (lag {lag / RATE:.3f} s)")
    return visqol_score(ref, deg, RATE)


def pair_folders(ref_dir, deg_dir):
    """Match <clip-id>.wav in both folders.

    Returns ([(id, ref, deg)], recordings with no reference, references with no recording).
    """
    refs = {p.stem: p for p in Path(ref_dir).glob("*.wav")}
    degs = {p.stem: p for p in Path(deg_dir).glob("*.wav")}
    pairs = [(c, refs[c], degs[c]) for c in sorted(degs) if c in refs]
    no_ref = sorted(set(degs) - set(refs))
    no_rec = sorted(set(refs) - set(degs))
    return pairs, no_ref, no_rec


def _fail(clip, err):
    """Report one failed clip as a JSON line on stdout and a message on stderr."""
    print(json.dumps({"clip": clip, "error": str(err)}), flush=True)
    print(f"{clip}: {err}", file=sys.stderr)


def cmd_visqol(a):
    ref, deg = Path(a.ref), Path(a.deg)
    if ref.is_file() and deg.is_file():
        print(json.dumps({"visqol_moslqo": round(visqol_files(ref, deg), 4)}))
        return 0
    if ref.is_dir() and deg.is_dir():
        pairs, no_ref, no_rec = pair_folders(ref, deg)
        if not pairs:
            print(f"no clip ids in common between {ref} and {deg}", file=sys.stderr)
            return 2
        failed = False
        for clip in no_ref:
            print(f"no clean reference for {clip}", file=sys.stderr)
        for clip in no_rec:
            print(f"no recording for {clip} (reference exists)", file=sys.stderr)
        for clip, r, d in pairs:
            try:
                print(json.dumps({"clip": clip, "visqol_moslqo": round(visqol_files(r, d), 4)}), flush=True)
            except ValueError as e:
                _fail(clip, e)
                failed = True
        return 1 if (failed or no_ref or no_rec) else 0
    print("--ref and --deg must both be files or both be folders", file=sys.stderr)
    return 2


# ---- DNSMOS -------------------------------------------------------------------------------

def _expected_model_sha(name=DNSMOS_SOURCES_NAME):
    with open(CORPUS / "SOURCES.tsv", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["name"] == name:
                return row["sha256"].lower()
    return None


def load_model(path):
    """Open the DNSMOS ONNX session after checking the file against the SHA-256 in SOURCES.tsv."""
    path = Path(path)
    if not path.is_file():
        sys.exit(f"DNSMOS model not found at {path}. Run: python3 tools/call-lab/corpus/fetch.py --only {DNSMOS_SOURCES_NAME}")
    want = _expected_model_sha()
    got = hashlib.sha256(path.read_bytes()).hexdigest()
    if want is None or got != want:
        print(f"{path}: SHA-256 {got} does not match SOURCES.tsv ({want})", file=sys.stderr)
        sys.exit(2)
    try:
        import onnxruntime as ort
    except ImportError:
        sys.exit("onnxruntime is not installed. Create the venv as described in scoring/README.md.")
    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def dnsmos_score(audio, rate, model):
    """SIG, BAK, OVRL (1..5) of a mono float clip. `model` is a path or an open session."""
    np = _np()
    sess = model if hasattr(model, "run") else load_model(model)
    if rate != RATE:
        audio = resample(audio, rate, RATE)
    audio = np.asarray(audio, dtype="float64")
    need = int(DNSMOS_SECONDS * RATE)
    while len(audio) < need:  # the reference script repeats short clips
        audio = np.append(audio, audio)
    hops = int(math.floor(len(audio) / RATE) - DNSMOS_SECONDS) + 1
    raw = []
    for i in range(hops):
        seg = audio[i * RATE:int((i + DNSMOS_SECONDS) * RATE)]
        if len(seg) < need:
            continue
        out = sess.run(None, {"input_1": seg.astype("float32")[np.newaxis, :]})[0][0]
        raw.append(out)
    sig, bak, ovr = np.mean(np.asarray(raw, dtype="float64"), axis=0)
    clip = lambda v: round(float(min(5.0, max(1.0, v))), 4)  # noqa: E731
    return {"sig": clip(np.polyval(_POLY_SIG, sig)),
            "bak": clip(np.polyval(_POLY_BAK, bak)),
            "ovrl": clip(np.polyval(_POLY_OVR, ovr))}


def cmd_dnsmos(a):
    src = Path(a.input)
    sess = load_model(a.model)
    if src.is_file():
        audio, rate = load(src)
        print(json.dumps(dnsmos_score(audio, rate, sess)))
        return 0
    if src.is_dir():
        files = sorted(src.glob("*.wav"))
        if not files:
            print(f"no .wav files in {src}", file=sys.stderr)
            return 2
        failed = False
        for p in files:
            try:
                audio, rate = load(p)
                print(json.dumps({"clip": p.stem, **dnsmos_score(audio, rate, sess)}), flush=True)
            except ValueError as e:
                _fail(p.stem, e)
                failed = True
        return 1 if failed else 0
    print(f"{src} is not a file or folder", file=sys.stderr)
    return 2


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("visqol", help="ViSQOL v3 speech MOS-LQO against a clean reference")
    v.add_argument("--ref", required=True, help="clean reference WAV, or a folder of <clip-id>.wav")
    v.add_argument("--deg", required=True, help="recording WAV, or a folder of <clip-id>.wav")
    v.set_defaults(fn=cmd_visqol)
    d = sub.add_parser("dnsmos", help="DNSMOS P.835 SIG/BAK/OVRL, no reference needed")
    d.add_argument("--in", dest="input", required=True, help="WAV file or folder of WAV files")
    d.add_argument("--model", default=str(DEFAULT_MODEL), help="sig_bak_ovr.onnx (default: fetched by corpus/fetch.py)")
    d.set_defaults(fn=cmd_dnsmos)
    a = p.parse_args(argv)
    try:
        return a.fn(a)
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
