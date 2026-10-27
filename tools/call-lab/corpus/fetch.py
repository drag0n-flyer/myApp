#!/usr/bin/env python3
"""Download the pinned corpus archives and check SHA-256 against SOURCES.tsv
(columns: name url sha256 license). Archives go to <dest>/archives/. Exit 2 on a hash
mismatch, an unpinned row (sha256 not 64 hex digits) or a failed download. Never commit the data."""
import argparse
import csv
import hashlib
import os
import re
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
            if a.extract:
                extract(path, dest / row["name"])
        except (ValueError, OSError) as e:
            print(e, file=sys.stderr)
            status = 2
    return status


if __name__ == "__main__":
    sys.exit(main())
