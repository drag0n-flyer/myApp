"""Tests for the corpus tools. Standard library only, no network."""
import hashlib
import io
import math
import os
import random
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fetch
import make_stimulus
import mix_noise
import wavio

RATE = 48000


def tone(seconds, freq=220.0, amp=8000):
    n = int(seconds * RATE)
    return [int(amp * math.sin(2 * math.pi * freq * i / RATE)) for i in range(n)]


class StimulusTests(unittest.TestCase):
    def test_stimulus_activity_ratio(self):
        with tempfile.TemporaryDirectory() as d:
            for i, f in enumerate((200.0, 300.0, 440.0)):
                wavio.write_wav(Path(d) / f"s{i}.wav", tone(60, f), RATE)
            out = Path(d) / "stimulus.wav"
            rc = make_stimulus.main(
                ["--speech-dir", d, "--out", str(out), "--seconds", "300", "--seed", "1"]
            )
            self.assertEqual(rc, 0)
            samples, rate = wavio.read_wav(out)
            self.assertEqual(rate, RATE)
            self.assertAlmostEqual(len(samples) / rate, 300.0, delta=0.02)
            ratio = make_stimulus.activity_ratio(samples, rate)
            self.assertGreaterEqual(ratio, 0.48)
            self.assertLessEqual(ratio, 0.52)

    def test_stimulus_is_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            wavio.write_wav(Path(d) / "a.wav", tone(30), RATE)
            outs = []
            for n in range(2):
                o = Path(d) / f"o{n}.wav"
                make_stimulus.main(["--speech-dir", d, "--out", str(o), "--seconds", "60", "--seed", "7"])
                outs.append(o.read_bytes())
            self.assertEqual(outs[0], outs[1])


class MixTests(unittest.TestCase):
    def test_mix_snr(self):
        rng = random.Random(3)
        # "speech": tone bursts with gaps, so only active frames count.
        clean = []
        for k in range(10):
            clean += tone(1.0, 250.0 + 40 * k, 6000) + [0] * RATE
        noise = [int(rng.gauss(0, 3000)) for _ in range(5 * RATE)]
        with tempfile.TemporaryDirectory() as d:
            c, n, o = (Path(d) / x for x in ("c.wav", "n.wav", "o.wav"))
            wavio.write_wav(c, clean, RATE)
            wavio.write_wav(n, noise, RATE)
            rc = mix_noise.main(["--clean", str(c), "--noise", str(n), "--snr", "5", "--out", str(o)])
            self.assertEqual(rc, 0)
            mixed, rate = wavio.read_wav(o)
            self.assertEqual(len(mixed), len(clean))
            residual = [m - s for m, s in zip(mixed, clean)]
            speech_rms = wavio.active_rms(clean, rate)
            noise_rms = wavio.rms(residual)
            snr = 20 * math.log10(speech_rms / noise_rms)
            self.assertAlmostEqual(snr, 5.0, delta=0.2)


class FetchTests(unittest.TestCase):
    def _fixture(self, d, sha):
        archive = Path(d) / "src" / "a.tar.gz"
        archive.parent.mkdir()
        archive.write_bytes(b"not really an archive")
        sources = Path(d) / "SOURCES.tsv"
        sources.write_text(
            "name\turl\tsha256\tlicense\n"
            f"demo\t{archive.as_uri()}\t{sha}\tCC0\n"
        )
        return archive, sources

    def test_fetch_rejects_bad_hash(self):
        with tempfile.TemporaryDirectory() as d:
            _, sources = self._fixture(d, "0" * 64)
            dest = Path(d) / "data"
            with redirect_stderr(io.StringIO()):
                rc = fetch.main(["--sources", str(sources), "--dest", str(dest)])
            self.assertEqual(rc, 2)
            self.assertEqual(list(dest.glob("**/*.tar.gz")), [])

    def test_fetch_accepts_good_hash(self):
        with tempfile.TemporaryDirectory() as d:
            archive, sources = self._fixture(d, hashlib.sha256(b"not really an archive").hexdigest())
            dest = Path(d) / "data"
            with redirect_stdout(io.StringIO()):
                rc = fetch.main(["--sources", str(sources), "--dest", str(dest)])
            self.assertEqual(rc, 0)
            self.assertEqual((dest / "archives" / "a.tar.gz").read_bytes(), archive.read_bytes())

    def test_fetch_refuses_unverified_row(self):
        with tempfile.TemporaryDirectory() as d:
            _, sources = self._fixture(d, "TODO-verify")
            with redirect_stderr(io.StringIO()):
                rc = fetch.main(["--sources", str(sources), "--dest", str(Path(d) / "data")])
            self.assertEqual(rc, 2)

    def test_speech_clip_is_extracted_from_librispeech_archive(self):
        import tarfile
        with tempfile.TemporaryDirectory() as d:
            archive = Path(d) / "test-clean.tar.gz"
            payload = b"fLaC fake"
            with tarfile.open(archive, "w:gz") as t:
                for name in ("LibriSpeech/test-clean/1089/134686/1089-134686-0001.flac", fetch.SPEECH_CLIP_MEMBER):
                    info = tarfile.TarInfo(name)
                    info.size = len(payload)
                    t.addfile(info, io.BytesIO(payload))
            calls = []
            out = fetch.extract_speech_clip(archive, Path(d) / "speech48k",
                                            convert=lambda src, dst: (calls.append((src.name, dst.name)), dst.write_bytes(b"wav")))
            self.assertEqual(out.name, "1089-134686-0000.wav")
            self.assertEqual(calls, [("1089-134686-0000.flac", "1089-134686-0000.wav")])
            self.assertEqual(sorted(p.name for p in (Path(d) / "speech48k").iterdir()), ["1089-134686-0000.wav"])

    def test_fetch_extracts_speech_clip_for_librispeech_row(self):
        import tarfile
        with tempfile.TemporaryDirectory() as d:
            archive = Path(d) / "test-clean.tar.gz"
            with tarfile.open(archive, "w:gz") as t:
                info = tarfile.TarInfo(fetch.SPEECH_CLIP_MEMBER)
                info.size = 4
                t.addfile(info, io.BytesIO(b"fLaC"))
            sources = Path(d) / "SOURCES.tsv"
            sources.write_text("name\turl\tsha256\tlicense\n"
                               f"librispeech-test-clean\t{archive.as_uri()}\t{hashlib.sha256(archive.read_bytes()).hexdigest()}\tCC BY 4.0\n")
            dest = Path(d) / "data"
            fetch.convert_flac_to_wav = lambda src, dst: dst.write_bytes(b"wav")
            with redirect_stdout(io.StringIO()):
                rc = fetch.main(["--sources", str(sources), "--dest", str(dest)])
            self.assertEqual(rc, 0)
            self.assertTrue((dest / "speech48k" / "1089-134686-0000.wav").exists())


if __name__ == "__main__":
    unittest.main()
