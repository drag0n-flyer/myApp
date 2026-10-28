#!/usr/bin/env python3
"""Check Swift package licenses against docs/security/license-allowlist.md.

Reads every Package.resolved under apps/apple/ and fails when a pinned package
identity is missing from the `swift-packages` table of the allowlist, when its
version differs from the recorded one, or when its row is still `pending`.
It never fetches licenses: the table is the owner's record.
Standard library only.

Usage: check-swift-licenses.py [--apps DIR] [--allowlist PATH]
Exit status: 0 when there are no findings, 1 otherwise.
"""

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APPS = REPO_ROOT / "apps" / "apple"
DEFAULT_ALLOWLIST = REPO_ROOT / "docs" / "security" / "license-allowlist.md"
SECTION = "swift-packages"


def load_table(allowlist):
    """Return {identity: (version, spdx, approved_by)} from the swift-packages table."""
    rows = {}
    in_section = False
    for line in Path(allowlist).read_text().splitlines():
        if line.startswith("#"):
            in_section = line.lstrip("#").strip() == SECTION
            continue
        if not in_section or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 4 or cells[0] == "identity" or set(cells[0]) <= set("-: "):
            continue
        rows[cells[0].lower()] = (cells[1], cells[2], cells[3])
    return rows


def pins(path):
    """Yield (identity, version) for every pin in a Package.resolved (v1, v2 or v3)."""
    data = json.loads(path.read_text())
    items = data.get("pins")
    if items is None:
        items = data.get("object", {}).get("pins", [])
    for pin in items:
        identity = pin.get("identity") or pin.get("package", "")
        state = pin.get("state", {})
        version = state.get("version") or state.get("branch") or state.get("revision", "")
        yield identity.lower(), version


def check(apps, allowlist):
    table = load_table(allowlist)
    problems = []
    for path in sorted(Path(apps).rglob("Package.resolved")):
        if ".build" in path.parts or "checkouts" in path.parts:
            continue
        for identity, version in pins(path):
            row = table.get(identity)
            if row is None:
                problems.append(f"{path}: package '{identity}' {version} is not in the swift-packages table")
            elif row[2].lower().startswith("pending"):
                problems.append(f"{path}: package '{identity}' is still pending owner approval")
            elif row[0] != version:
                problems.append(f"{path}: package '{identity}' is pinned at {version} but approved at {row[0]}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apps", default=str(DEFAULT_APPS))
    parser.add_argument("--allowlist", default=str(DEFAULT_ALLOWLIST))
    args = parser.parse_args()
    problems = check(args.apps, args.allowlist)
    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
