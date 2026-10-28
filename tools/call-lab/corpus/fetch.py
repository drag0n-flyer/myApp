#!/usr/bin/env python3
"""Download the pinned corpus archives and check SHA-256 against SOURCES.tsv
(columns: name url sha256 license). Archives go to <dest>/archives/. Exit 2 on a hash
mismatch, an unpinned row (sha256 not 64 hex digits) or a failed download. Never commit the data."""
import argparse
import csv
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent


def fetch_one(row, archives):
    name, url, want = row["name"], row["url"], row["sha256"].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", want):
        raise ValueError(f"{name}: sha256 is not pinned ({row['sha256']!r}); verify it first")
    filename = os.path.basename(urllib.parse.urlparse(url).path)
    if not filename:
        raise ValueError(f"{name}: cannot derive a file name from {url}")
    target = archives / filename
    h = hashlib.sha256()
    fd, tmp = tempfile.mkstemp(dir=archives, suffix=".part")
    try:
        with os.fdopen(fd, "wb") as f, urllib.request.urlopen(url, timeout=60) as r:
            while chunk := r.read(1 << 20):
                h.update(chunk)
                f.write(chunk)
        if h.hexdigest() != want:
            raise ValueError(f"{name}: SHA-256 mismatch (got {h.hexdigest()}, want {want})")
        os.replace(tmp, target)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    return target


def extract(archive, dest):
    if archive.name.endswith((".tar.gz", ".tgz", ".tar")):
        with tarfile.open(archive) as t:
            t.extractall(dest, filter="data")


SPEECH_CLIP_MEMBER = "LibriSpeech/test-clean/1089/134686/1089-134686-0000.flac"


def convert_flac_to_wav(src, dst):
    """48 kHz mono 16-bit WAV, with afconvert (macOS) or ffmpeg."""
    if shutil.which("afconvert"):
        cmd = ["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", str(src), str(dst)]
    elif shutil.which("ffmpeg"):
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", str(src), "-ac", "1", "-ar", "48000", "-c:a", "pcm_s16le", str(dst)]
    else:
        raise OSError("neither afconvert nor ffmpeg found; cannot convert the speech clip")
    subprocess.run(cmd, check=True)


def extract_speech_clip(archive, out_dir, convert=None):
    """Extract one fixed LibriSpeech clip and convert it to <out_dir>/<id>.wav (used by the >= 4.5 scoring test)."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = Path(SPEECH_CLIP_MEMBER).stem
    wav = out_dir / f"{stem}.wav"
    with tempfile.TemporaryDirectory() as tmp:
        flac = Path(tmp) / f"{stem}.flac"
        with tarfile.open(archive) as t:
            flac.write_bytes(t.extractfile(SPEECH_CLIP_MEMBER).read())
        (convert or convert_flac_to_wav)(flac, wav)
    return wav


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sources", default=str(HERE / "SOURCES.tsv"))
    p.add_argument("--dest", default=str(HERE / "data"))
    p.add_argument("--only", action="append", help="fetch only this name (repeatable)")
    p.add_argument("--extract", action="store_true", help="also unpack tar archives into <dest>/<name>/")
    a = p.parse_args(argv)
    dest = Path(a.dest)
    archives = dest / "archives"
    archives.mkdir(parents=True, exist_ok=True)
    with open(a.sources, newline="") as f:
        rows = [r for r in csv.DictReader(f, delimiter="\t") if r["name"] and not r["name"].startswith("#")]
    if a.only:
        rows = [r for r in rows if r["name"] in a.only]
    status = 0
    for row in rows:
        try:
            path = fetch_one(row, archives)
            print(f"ok {row['name']} {path}")
            if row["name"] == "librispeech-test-clean":
                try:
                    print(f"ok speech clip {extract_speech_clip(path, dest / 'speech48k', convert_flac_to_wav)}")
                except (OSError, KeyError, subprocess.CalledProcessError) as e:
                    print(f"warning: speech clip not prepared ({e}); the >= 4.5 ViSQOL test will skip", file=sys.stderr)
            if a.extract:
                extract(path, dest / row["name"])
        except (ValueError, OSError) as e:
            print(e, file=sys.stderr)
            status = 2
    return status


if __name__ == "__main__":
    sys.exit(main())
