#!/usr/bin/env python3
"""Build the open-source notices JSON shown on the app's licenses page.

Sources:
- Rust crates: `cargo metadata --locked` for mlsffi, normal (not build, not dev)
  dependencies only. License text comes from the license files in the cargo
  registry copy of each crate; a short notice names the license when there is none.
- Vendored components: LICENSE files in this repository (RNNoise).
- WebRTC and Opus: pinned entries from tools/webrtc/VERSION, marked pending until
  the first WebRTC release provides the license file.

Standard library only. Usage: generate-oss-notices.py [--manifest PATH] [--output PATH]
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "apps/apple/Packages/SecureMessaging/mlsffi/Cargo.toml"
DEFAULT_OUTPUT = REPO_ROOT / "apps/apple/Packages/AppCore/Sources/AppCore/Resources/oss-notices.json"
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


def crate_license_text(package):
    crate_dir = Path(package["manifest_path"]).parent
    files = []
    if crate_dir.is_dir():
        files = sorted(f for f in crate_dir.iterdir() if f.is_file() and LICENSE_FILE.match(f.name))
    if files:
        parts = [f.read_text(encoding="utf-8", errors="replace").strip() for f in files]
        return "\n\n----\n\n".join(parts)
    text = f"{package['name']} is licensed under {package.get('license') or 'unknown terms'}."
    if package.get("repository"):
        text += f"\nThe license text is in the project's source: {package['repository']}"
    return text


def read_version_file(path):
    values = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep:
            values[key.strip()] = value.strip()
    return values


def vendored_entries(root):
    version = read_version_file(root / "tools/webrtc/VERSION")
    rnnoise_dir = root / "tools/call-lab/Packages/RNNoiseLab"
    commit = re.search(r"Pinned commit: `([0-9a-f]{7,40})`", (rnnoise_dir / "VENDOR.md").read_text())
    return [
        {"name": "RNNoise", "version": commit.group(1)[:7] if commit else "unknown",
         "license": "BSD-3-Clause", "text": (rnnoise_dir / "COPYING").read_text().strip(),
         "pending": False},
        {"name": "WebRTC", "version": f"m{version['WEBRTC_MILESTONE']}", "license": "BSD-3-Clause",
         "text": PENDING_TEXT.format(license="BSD-3-Clause"), "pending": True},
        {"name": "Opus", "version": version["OPUS_VERSION"], "license": "BSD-3-Clause",
         "text": PENDING_TEXT.format(license="BSD-3-Clause"), "pending": True},
    ]


def crate_entries(metadata):
    return [{"name": p["name"], "version": p["version"], "license": p.get("license") or "unknown",
             "text": crate_license_text(p), "pending": False} for p in normal_packages(metadata)]


def notices_json(entries):
    ordered = sorted(entries, key=lambda e: (e["name"].lower(), e["version"]))
    return json.dumps({"components": ordered}, indent=1, ensure_ascii=False) + "\n"


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
    Path(args.output).write_text(notices_json(entries), encoding="utf-8")
    print(f"wrote {len(entries)} components to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
