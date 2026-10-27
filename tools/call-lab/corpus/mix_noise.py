#!/usr/bin/env python3
"""Mix noise into clean speech at a target SNR. SNR = clean RMS over active speech frames
(20 ms, above -40 dBFS) against RMS of the scaled noise over the whole mix. Mono 16-bit WAV."""
import argparse
import sys

from wavio import active_rms, activity_ratio, read_wav, rms, write_wav

__all__ = ["activity_ratio", "mix", "main"]


def mix(clean, noise, rate, snr_db):
    speech_rms = active_rms(clean, rate)
    if speech_rms == 0 or not noise:
        raise ValueError("clean has no active speech, or noise is empty")
    reps = len(clean) // len(noise) + 1
    noise = (noise * reps)[:len(clean)]
    noise_rms = rms(noise)
    if noise_rms == 0:
        raise ValueError("noise is silent")
    gain = speech_rms / (noise_rms * 10 ** (snr_db / 20))
    return [c + gain * n for c, n in zip(clean, noise)]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--clean", required=True)
    p.add_argument("--noise", required=True)
    p.add_argument("--snr", type=float, required=True, help="dB")
    p.add_argument("--out", required=True)
    a = p.parse_args(argv)
    try:
        clean, r1 = read_wav(a.clean)
        noise, r2 = read_wav(a.noise)
        if r1 != r2:
            raise ValueError(f"sample rates differ: {r1} vs {r2}")
        write_wav(a.out, mix(clean, noise, r1, a.snr), r1)
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
