#!/usr/bin/env python3
"""Build the fixed "conversation" stimulus: talk spans of 4-8 s alternating with silences,
so that energy-VAD speech activity is about 50%. Inputs: 48 kHz mono 16-bit WAV files
(convert on the Mac with: afconvert -f WAVE -d LEI16@48000 -c 1 in out.wav)."""
import argparse
import random
import sys
from pathlib import Path

from wavio import FRAME_SECONDS, activity_ratio, read_wav, write_wav

__all__ = ["activity_ratio", "build", "main"]

RATE = 48000
TARGET = 0.5
MIN_TALK, MAX_TALK = 4.0, 8.0


def build(speech, seconds, seed, rate=RATE):
    frame = int(round(rate * FRAME_SECONDS))
    total = int(round(seconds * rate))
    rng = random.Random(seed)
    out = []
    pos = 0  # position in the speech stream, taken in order and wrapping
    cum_active = 0.0
    while len(out) < total:
        span = rng.randint(int(MIN_TALK / FRAME_SECONDS), int(MAX_TALK / FRAME_SECONDS)) * frame
        chunk = []
        while len(chunk) < span:
            take = min(span - len(chunk), len(speech) - pos)
            chunk.extend(speech[pos:pos + take])
            pos = (pos + take) % len(speech)
        out.extend(chunk)
        cum_active += activity_ratio(chunk, rate) * len(chunk)
        # Silence so that cumulative activity returns to the target.
        gap = int(round((cum_active / TARGET - len(out)) / frame)) * frame
        out.extend([0] * max(0, gap))
    return out[:total]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--speech-dir", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--seconds", type=float, default=300)
    p.add_argument("--seed", type=int, default=1)
    a = p.parse_args(argv)
    files = sorted(Path(a.speech_dir).glob("*.wav"))
    if not files:
        print(f"no .wav files in {a.speech_dir}", file=sys.stderr)
        return 2
    speech = []
    for f in files:
        samples, rate = read_wav(f)
        if rate != RATE:
            print(f"{f}: sample rate {rate}, need {RATE}", file=sys.stderr)
            return 2
        speech.extend(samples)
    write_wav(a.out, build(speech, a.seconds, a.seed), RATE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
