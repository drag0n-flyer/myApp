"""Tests for score.py. Run inside the scoring venv: .venv/bin/python -m unittest -v

Tests that need numpy, speequal or the DNSMOS model skip only when those are absent.
"""
import importlib.util
import json
import math
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "corpus"))

HAVE_NUMPY = importlib.util.find_spec("numpy") is not None
HAVE_VISQOL = HAVE_NUMPY and importlib.util.find_spec("speequal") is not None
HAVE_ORT = HAVE_NUMPY and importlib.util.find_spec("onnxruntime") is not None
MODEL = HERE.parent / "corpus" / "data" / "archives" / "sig_bak_ovr.onnx"

# A real speech clip (mono 16-bit WAV): CALL_LAB_SPEECH_WAV, else the first file in corpus/data/speech48k/
# (clips converted from LibriSpeech as described in scoring/README.md).
_env = os.environ.get("CALL_LAB_SPEECH_WAV")
REAL_SPEECH = ([Path(_env)] if _env else sorted((HERE.parent / "corpus" / "data" / "speech48k").glob("*.wav")))[:1]
NEED_SPEECH = "no real speech clip: set CALL_LAB_SPEECH_WAV or put converted LibriSpeech WAVs in corpus/data/speech48k/"
NEED_NUMPY = "numpy is not installed (create the venv: see scoring/README.md)"
NEED_VISQOL = "speequal is not installed (create the venv: see scoring/README.md)"
NEED_DNSMOS = "onnxruntime or the DNSMOS model is missing (see scoring/README.md)"


def speechlike(seconds, rate, seed=1):
    """Band-limited noise with a syllable-rate envelope: deterministic, aperiodic."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = int(seconds * rate)
    x = rng.standard_normal(n)
    k = np.ones(8) / 8
    x = np.convolve(x, k, mode="same")
    t = np.arange(n) / rate
    env = 0.55 + 0.45 * np.sin(2 * math.pi * 3.1 * t) * np.sin(2 * math.pi * 0.7 * t + 1)
    return (0.2 * x * env / np.max(np.abs(x * env))).astype("float64")


@unittest.skipUnless(HAVE_NUMPY, NEED_NUMPY)
class AlignTests(unittest.TestCase):
    def test_alignment_recovers_lag(self):
        import numpy as np
        import score
        rate = 16000
        ref = speechlike(6, rate)
        d = int(0.37 * rate)
        deg = np.concatenate([np.zeros(d), ref])
        lag = score.find_lag(ref, deg, rate, max_lag_s=2.0)
        self.assertLess(abs(lag / rate - 0.37), 0.001)
        r, g = score.align(ref, deg, lag)
        self.assertEqual(len(r), len(g))
        self.assertGreater(len(r), 5 * rate)
        self.assertLess(float(np.max(np.abs(r - g))), 1e-9)

    def test_alignment_negative_lag(self):
        import numpy as np
        import score
        rate = 16000
        ref = speechlike(6, rate, seed=2)
        deg = ref[int(0.25 * rate):]
        lag = score.find_lag(ref, deg, rate, max_lag_s=2.0)
        self.assertLess(abs(lag / rate + 0.25), 0.001)

    def test_lag_is_limited_to_max(self):
        import numpy as np
        import score
        rate = 16000
        ref = speechlike(8, rate, seed=3)
        deg = np.concatenate([np.zeros(int(3 * rate)), ref])
        lag = score.find_lag(ref, deg, rate, max_lag_s=2.0)
        self.assertLessEqual(abs(lag), 2 * rate)

    def test_resample_length_and_tone(self):
        import numpy as np
        import score
        t = np.arange(48000) / 48000
        x = np.sin(2 * math.pi * 1000 * t)
        y = score.resample(x, 48000, 16000)
        self.assertEqual(len(y), 16000)
        self.assertAlmostEqual(float(np.sqrt(np.mean(y ** 2))), 0.7071, delta=0.01)


class FolderMatchTests(unittest.TestCase):
    def test_pair_by_clip_id(self):
        import score
        with tempfile.TemporaryDirectory() as d:
            ref, deg = Path(d, "ref"), Path(d, "deg")
            ref.mkdir()
            deg.mkdir()
            for n in ("a", "b", "c"):
                (ref / f"{n}.wav").touch()
            for n in ("b", "c", "z"):
                (deg / f"{n}.wav").touch()
            pairs, missing = score.pair_folders(ref, deg)
            self.assertEqual([p[0] for p in pairs], ["b", "c"])
            self.assertEqual(missing, ["z"])


@unittest.skipUnless(HAVE_VISQOL, NEED_VISQOL)
class VisqolTests(unittest.TestCase):
    @unittest.skipUnless(REAL_SPEECH, NEED_SPEECH)
    def test_visqol_identical_is_high(self):
        # Real speech: ViSQOL's default (lattice) mapping tops out near 4.5-4.6 for identical
        # speech, but gives ~3.9 for synthetic noise, so the 4.5 bound needs a real clip.
        import score
        self.assertGreaterEqual(score.visqol_files(REAL_SPEECH[0], REAL_SPEECH[0]), 4.5)

    def test_visqol_identical_synthetic_is_fair_or_better(self):
        import score
        x = speechlike(8, 16000)
        self.assertGreaterEqual(score.visqol_score(x, x.copy(), 16000), 3.5)

    def test_visqol_noise_is_lower(self):
        import numpy as np
        import score
        x = speechlike(8, 16000)
        y = x + 0.1 * np.random.default_rng(5).standard_normal(len(x))
        self.assertLess(score.visqol_score(x, y, 16000), score.visqol_score(x, x.copy(), 16000))

    def test_cli_single_file_with_lag_and_rate(self):
        import numpy as np
        import wavio
        rate = 48000
        ref = speechlike(8, rate)
        deg = np.concatenate([np.zeros(int(0.37 * rate)), ref])
        with tempfile.TemporaryDirectory() as d:
            rp, dp = Path(d, "ref.wav"), Path(d, "deg.wav")
            wavio.write_wav(rp, (ref * 32767).astype(int).tolist(), rate)
            wavio.write_wav(dp, (deg * 32767).astype(int).tolist(), rate)
            out = subprocess.run([sys.executable, "-I", str(HERE / "score.py"), "visqol",
                                  "--ref", str(rp), "--deg", str(dp)],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            result = json.loads(out.stdout.strip().splitlines()[-1])
            self.assertEqual(list(result), ["visqol_moslqo"])
            self.assertGreaterEqual(result["visqol_moslqo"], 3.5)

    def test_cli_folder_mode(self):
        import wavio
        rate = 48000
        with tempfile.TemporaryDirectory() as d:
            ref, deg = Path(d, "ref"), Path(d, "deg")
            ref.mkdir()
            deg.mkdir()
            for i, n in enumerate(("fan-01", "wind-02")):
                x = (speechlike(6, rate, seed=10 + i) * 32767).astype(int).tolist()
                wavio.write_wav(ref / f"{n}.wav", x, rate)
                wavio.write_wav(deg / f"{n}.wav", x, rate)
            out = subprocess.run([sys.executable, "-I", str(HERE / "score.py"), "visqol",
                                  "--ref", str(ref), "--deg", str(deg)],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            lines = [json.loads(l) for l in out.stdout.strip().splitlines()]
            self.assertEqual([l["clip"] for l in lines], ["fan-01", "wind-02"])
            for l in lines:
                self.assertGreaterEqual(l["visqol_moslqo"], 3.5)


@unittest.skipUnless(HAVE_ORT and MODEL.exists(), NEED_DNSMOS)
class DnsmosTests(unittest.TestCase):
    def test_dnsmos_shape(self):
        import score
        x = speechlike(10, 16000)
        r = score.dnsmos_score(x, 16000, MODEL)
        self.assertEqual(sorted(r), ["bak", "ovrl", "sig"])
        for k, v in r.items():
            self.assertGreaterEqual(v, 1.0, k)
            self.assertLessEqual(v, 5.0, k)

    def test_dnsmos_short_clip_is_padded(self):
        import score
        r = score.dnsmos_score(speechlike(2, 16000), 16000, MODEL)
        self.assertEqual(sorted(r), ["bak", "ovrl", "sig"])

    def test_cli_folder_mode(self):
        import wavio
        with tempfile.TemporaryDirectory() as d:
            for n in ("a", "b"):
                wavio.write_wav(Path(d, f"{n}.wav"), (speechlike(10, 48000) * 32767).astype(int).tolist(), 48000)
            out = subprocess.run([sys.executable, "-I", str(HERE / "score.py"), "dnsmos", "--in", d],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            lines = [json.loads(l) for l in out.stdout.strip().splitlines()]
            self.assertEqual([l["clip"] for l in lines], ["a", "b"])
            for l in lines:
                for k in ("sig", "bak", "ovrl"):
                    self.assertTrue(1.0 <= l[k] <= 5.0)


class ModelHashTests(unittest.TestCase):
    @unittest.skipUnless(HAVE_NUMPY, NEED_NUMPY)
    def test_wrong_model_hash_is_refused(self):
        import score
        with tempfile.TemporaryDirectory() as d:
            m = Path(d, "sig_bak_ovr.onnx")
            m.write_bytes(b"not the model")
            with self.assertRaises(SystemExit) as cm:
                score.load_model(m)
            self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
