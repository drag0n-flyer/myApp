#!/usr/bin/env python3
"""Deterministic synthetic fixtures for NoiseTests (standard library only, CC0).

speech-clean.wav: 5 s, 48 kHz, 16-bit mono. 0.5 s near-silence lead-in, then a
  series of voiced syllables (glottal pulse train through formant filters, moving pitch).
speech-fan.wav: the same speech plus fan-like noise (low-passed noise plus a blade-pass hum) over the full 5 s.
Run: python3 -I scripts/make-noise-fixtures.py
"""
import math, random, struct, wave, os

RATE = 48000
SECONDS = 5.0
LEAD = 0.5
N = int(RATE * SECONDS)
rng = random.Random(20261028)

def resonator(x, f, bw):
    r = math.exp(-math.pi * bw / RATE)
    a1 = -2 * r * math.cos(2 * math.pi * f / RATE)
    a2 = r * r
    g = 1 - r
    y1 = y2 = 0.0
    out = []
    for v in x:
        y = g * v - a1 * y1 - a2 * y2
        out.append(y)
        y2, y1 = y1, y
    return out

VOWELS = [(730, 1090, 2440), (270, 2290, 3010), (300, 870, 2240), (530, 1840, 2480), (660, 1720, 2410)]

def speech():
    out = [0.0] * N
    t = LEAD + 0.05
    k = 0
    while t < SECONDS - 0.15:
        dur = 0.16 + 0.06 * rng.random()
        n0, n1 = int(t * RATE), min(N, int((t + dur) * RATE))
        f0a = 105 + 40 * rng.random()
        f0b = f0a * (0.85 + 0.3 * rng.random())
        src = []
        phase = 0.0
        for i in range(n1 - n0):
            u = i / max(1, n1 - n0 - 1)
            f0 = f0a + (f0b - f0a) * u + 1.5 * math.sin(2 * math.pi * 5 * i / RATE)
            phase += f0 / RATE
            if phase >= 1.0:
                phase -= 1.0
                src.append(1.0)
            else:
                src.append(0.0)
        # soften pulses into a glottal-like source
        src = resonator(src, 90, 120)
        f1, f2, f3 = VOWELS[k % len(VOWELS)]
        y = resonator(src, f1, 80)
        y2 = resonator(src, f2, 110)
        y3 = resonator(src, f3, 160)
        for i in range(n1 - n0):
            u = i / max(1, n1 - n0 - 1)
            env = math.sin(math.pi * u) ** 0.7
            out[n0 + i] += env * (y[i] + 0.6 * y2[i] + 0.3 * y3[i])
        k += 1
        t += dur + (0.03 if k % 3 else 0.14) + 0.03 * rng.random()
    peak = max(abs(v) for v in out)
    return [v / peak * 0.35 for v in out]

def fan():
    white = [rng.gauss(0, 1) for _ in range(N)]
    lp = []
    a = 0.0
    for v in white:  # one-pole low-pass, about 1.5 kHz
        a += 0.18 * (v - a)
        lp.append(a)
    hum = [0.35 * math.sin(2 * math.pi * 120 * i / RATE) + 0.15 * math.sin(2 * math.pi * 240 * i / RATE) for i in range(N)]
    mix = [l + h for l, h in zip(lp, hum)]
    rms = math.sqrt(sum(v * v for v in mix) / N)
    return [v / rms * 0.04 for v in mix]  # about -28 dBFS

def write(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", max(-32768, min(32767, int(round(v * 32767))))) for v in x))

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(here, "..", "Tests", "CallMediaTests", "Fixtures")
s = speech()
clean = [v + 0.0005 * rng.gauss(0, 1) for v in s]
f = fan()
write(os.path.join(out_dir, "speech-clean.wav"), clean)
write(os.path.join(out_dir, "speech-fan.wav"), [a + b for a, b in zip(s, f)])
