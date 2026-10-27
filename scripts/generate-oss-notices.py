#!/usr/bin/env python3
"""Build the open-source notices JSON shown on the app's licenses page.

Sources:
- Canonical license texts: scripts/license-texts/ (SPDX), stored once and referenced by id.
- Rust crates: `cargo metadata --locked` for mlsffi, normal (not build, not dev)
  dependencies only. Each crate keeps its own license file text (copyright lines) when it ships one,
  next to the canonical text of its license.
- Vendored components: LICENSE files in this repository (RNNoise).
- WebRTC and Opus: pinned entries from tools/webrtc/VERSION, marked pending until
  the first WebRTC release provides the license file.

Standard library only. Usage: generate-oss-notices.py [--manifest PATH] [--output PATH]
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "apps/apple/Packages/SecureMessaging/mlsffi/Cargo.toml"
DEFAULT_OUTPUT = REPO_ROOT / "apps/apple/Packages/AppCore/Sources/AppCore/Resources/oss-notices.json"
CANONICAL_DIR = REPO_ROOT / "scripts/license-texts"
LICENSE_FILE = re.compile(r"^(licen[sc]e|copying|unlicense)", re.IGNORECASE)
PENDING_TEXT = ("The full license text is added with the first release of this component. "
                "It is licensed under {license}.")


def normal_packages(metadata):
    """Packages reachable from the root through normal dependency edges, root excluded."""
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    packages = {p["id"]: p for p in metadata["packages"]}
    root = metadata["resolve"]["root"]
    seen, stack = set(), [root]
    while stack:
        pkg_id = stack.pop()
        if pkg_id in seen:
            continue
        seen.add(pkg_id)
        for dep in nodes[pkg_id]["deps"]:
            if any(kind["kind"] is None for kind in dep["dep_kinds"]):
                stack.append(dep["pkg"])
    seen.discard(root)
    return [packages[i] for i in seen]


def license_ids(expression):
    """SPDX ids named in an expression, in order, without keywords or exceptions."""
    ids, skip_next = [], False
    for token in re.findall(r"[^\s()/]+", expression or ""):
        if skip_next:
            skip_next = False
        elif token == "WITH":
            skip_next = True
        elif token not in ("OR", "AND") and token not in ids:
            ids.append(token)
    return ids


def load_canonical(directory=CANONICAL_DIR):
    return {f.stem: f.read_text(encoding="utf-8").strip()
            for f in sorted(Path(directory).glob("*.txt")) if f.name != "SOURCE.txt"}


def crate_entry(package):
    crate_dir = Path(package["manifest_path"]).parent
    files = []
    if crate_dir.is_dir():
        files = sorted(f for f in crate_dir.iterdir() if f.is_file() and LICENSE_FILE.match(f.name))
    return {"name": package["name"], "version": package["version"],
            "license": package.get("license") or "unknown",
            "authors": ", ".join(package.get("authors") or []),
            "repository": package.get("repository"),
            "own_texts": [f.read_text(encoding="utf-8", errors="replace").strip() for f in files],
            "pending": False}


def _normalized(text):
    return " ".join(text.split())


def build_book(entries, canonical):
    """Components point at text ids; each distinct text is stored once."""
    texts, components = {}, []
    canonical_norm = {_normalized(t) for t in canonical.values()}
    for e in sorted(entries, key=lambda e: (e["name"].lower(), e["version"])):
        ids = [i for i in license_ids(e["license"]) if i in canonical]
        for i in ids:
            texts[i] = canonical[i]
        for own in e["own_texts"]:
            if not own or _normalized(own) in canonical_norm:
                continue
            text_id = "sha-" + hashlib.sha256(own.encode("utf-8")).hexdigest()[:12]
            texts[text_id] = own
            if text_id not in ids:
                ids.append(text_id)
        components.append({"name": e["name"], "version": e["version"], "license": e["license"],
                           "authors": e["authors"], "repository": e["repository"],
                           "textIds": ids, "pending": e["pending"]})
    return {"texts": dict(sorted(texts.items())), "components": components}


def read_version_file(path):
    values = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep:
            values[key.strip()] = value.strip()
    return values


def vendored_entries(root):
    version = read_version_file(root / "tools/webrtc/VERSION")
    # TODO: when calling/lane-m3 lands on main, read RNNoise from the copy the app ships,
    # apps/apple/Packages/CallMedia/Sources/RNNoise/LICENSE, not the call-lab copy.
    rnnoise_dir = root / "tools/call-lab/Packages/RNNoiseLab"
    commit = re.search(r"Pinned commit: `([0-9a-f]{7,40})`", (rnnoise_dir / "VENDOR.md").read_text())

    def entry(name, version, own, pending, authors=""):
        return {"name": name, "version": version, "license": "BSD-3-Clause", "authors": authors,
                "repository": None, "own_texts": [own], "pending": pending}

    pending = PENDING_TEXT.format(license="BSD-3-Clause")
    return [
        entry("RNNoise", commit.group(1)[:7] if commit else "unknown",
              (rnnoise_dir / "COPYING").read_text().strip(), False, "Xiph.Org Foundation, Mozilla, Amazon, Jean-Marc Valin"),
        entry("WebRTC", f"m{version['WEBRTC_MILESTONE']}", pending, True, "The WebRTC project authors"),
        entry("Opus", version["OPUS_VERSION"], pending, True, "Xiph.Org Foundation and contributors"),
    ]


def crate_entries(metadata):
    return [crate_entry(p) for p in normal_packages(metadata)]


def notices_json(entries, canonical):
    return json.dumps(build_book(entries, canonical), indent=1, ensure_ascii=False) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args()
    out = subprocess.run(
        ["cargo", "metadata", "--locked", "--format-version", "1", "--manifest-path", args.manifest],
        check=True, capture_output=True, text=True,
    ).stdout
    entries = crate_entries(json.loads(out)) + vendored_entries(REPO_ROOT)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(notices_json(entries, load_canonical()), encoding="utf-8")
    print(f"wrote {len(entries)} components to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
