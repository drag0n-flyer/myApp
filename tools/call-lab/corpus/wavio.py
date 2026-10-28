"""Shared WAV helpers (mono 16-bit PCM) and the activity measure. Standard library only."""
import array
import math
import sys
import wave

FRAME_SECONDS = 0.020
ACTIVE_DBFS = -40.0
_FULL_SCALE = 32768.0


def read_wav(path):
    """Return (samples as list of int, sample rate). Mono 16-bit PCM only."""
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise ValueError(f"{path}: need mono 16-bit PCM (convert with afconvert -f WAVE -d LEI16@48000 -c 1)")
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    a = array.array("h")
    a.frombytes(raw)
    if sys.byteorder == "big":
        a.byteswap()
    return list(a), rate


def write_wav(path, samples, rate):
    a = array.array("h", (max(-32768, min(32767, int(s))) for s in samples))
    if sys.byteorder == "big":
        a.byteswap()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(a.tobytes())


def rms(samples):
    if not samples:
        return 0.0
    return math.sqrt(sum(s * s for s in samples) / len(samples))


def _frames(samples, rate):
    n = int(round(rate * FRAME_SECONDS))
    for i in range(0, len(samples) - n + 1, n):
        yield samples[i:i + n]


def _is_active(frame):
    return rms(frame) / _FULL_SCALE > 10 ** (ACTIVE_DBFS / 20)


def activity_ratio(samples, rate):
    """Share of 20 ms frames whose RMS is above -40 dBFS."""
    total = active = 0
    for f in _frames(samples, rate):
        total += 1
        active += _is_active(f)
    return active / total if total else 0.0


def active_rms(samples, rate):
    """RMS over the samples of active frames only."""
    acc = []
    for f in _frames(samples, rate):
        if _is_active(f):
            acc.extend(f)
    return rms(acc)
