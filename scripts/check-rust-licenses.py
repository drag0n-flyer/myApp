#!/usr/bin/env python3
"""Check the license of every crate in the mlsffi dependency tree.

Uses `cargo metadata --locked` (no new tool). An SPDX expression passes when it
can be satisfied using only allowed licenses: OR needs one allowed side, AND
needs both. `X WITH exception` counts as X, and "/" counts as OR (old Cargo
style). Unknown or missing licenses fail.

Allowed: the owner's list (docs/security/license-allowlist.md) plus the Rust
extras below, which wait for the owner's confirmation.
Standard library only. Usage: check-rust-licenses.py [--manifest PATH]
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "apps/apple/Packages/SecureMessaging/mlsffi/Cargo.toml"

ALLOWED = {
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "0BSD",
    "Unlicense", "MPL-2.0",
    # Rust extra, approved by the owner 2026-10-28: permissive, needed by unicode-ident.
    "Unicode-3.0",
}

TOKEN = re.compile(r"\(|\)|[^\s()/]+|/")


def expression_allowed(expr):
    tokens = TOKEN.findall(expr or "")
    if not tokens:
        return False
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take():
        nonlocal pos
        pos += 1
        return tokens[pos - 1]

    def parse_or():
        result = parse_and()
        while peek() in ("OR", "/"):
            take()
            right = parse_and()
            result = result or right
        return result

    def parse_and():
        result = parse_atom()
        while peek() == "AND":
            take()
            right = parse_atom()
            result = result and right
        return result

    def parse_atom():
        tok = take()
        if tok == "(":
            result = parse_or()
            if peek() != ")":
                raise ValueError("missing ')'")
            take()
            return result
        ok = tok in ALLOWED
        if peek() == "WITH":
            take()
            take()
        return ok

    try:
        result = parse_or()
    except (IndexError, ValueError):
        return False
    return result and pos == len(tokens)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    args = parser.parse_args()
    out = subprocess.run(
        ["cargo", "metadata", "--locked", "--format-version", "1", "--manifest-path", args.manifest],
        check=True, capture_output=True, text=True,
    ).stdout
    packages = json.loads(out)["packages"]
    bad = [p for p in packages if p.get("source") is not None and not expression_allowed(p.get("license"))]
    for p in bad:
        print(f"{p['name']} {p['version']}: license '{p.get('license')}' is not allowed", file=sys.stderr)
    print(f"checked {len(packages)} crates, {len(bad)} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
