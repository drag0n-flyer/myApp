#!/usr/bin/env python3
"""Check the private-beta gate (docs/release/beta-gate.md). Standard library only.

Errors (always exit 1, even with --report): a malformed row, a `done` row with
no evidence, a `waived` row without "(<reason>, owner <date>)", an unknown status.

Gaps (exit 1 unless --report): any row that is not `done` or `waived`,
`TODO(plan 7)` in docs/protocol/README.md, no owner line in the threat model's
review log, a document under docs/legal with the lawyer-review banner but no
"lawyer reviewed <date>" line right under it (reported as open, never hidden).

Usage: check-beta-gate.py [--report] [--root DIR]
  --report  print everything, exit 0 when there are no errors (used by CI)
  (none)    strict mode: exit 0 only when the gate is met (used for the release)
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BANNER = "> **Draft — needs lawyer review before use.**"
EVIDENCE = re.compile(r"\]\([^)\s]+\)|https?://\S+|👤\s*\d{4}-\d{2}-\d{2}\s+\S+")
WAIVED = re.compile(r"^waived \((.+), owner \d{4}-\d{2}-\d{2}\)$")
OWNER_LINE = re.compile(r"👤\s*\d{4}-\d{2}-\d{2}\s+\S+")
LAWYER_LINE = re.compile(r"lawyer reviewed \d{4}-\d{2}-\d{2}", re.I)


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def parse_rows(text):
    rows, in_table = [], False
    for n, line in enumerate(text.splitlines(), 1):
        if not line.lstrip().startswith("|"):
            in_table = False
            continue
        c = cells(line)
        if [x.lower() for x in c] == ["item", "source", "evidence", "status"]:
            in_table = True
            continue
        if not in_table or all(re.fullmatch(r":?-{3,}:?", x) for x in c):
            continue
        rows.append((n, c))
    return rows


def check_rows(path, errors, open_items, counts):
    if not path.is_file():
        errors.append(f"{path}: missing")
        return
    rows = parse_rows(path.read_text(encoding="utf-8"))
    if not rows:
        errors.append(f"{path}: no table rows found")
    for n, c in rows:
        if len(c) != 4:
            errors.append(f"line {n}: expected 4 columns, found {len(c)}")
            continue
        item, _src, evidence, status = c
        evidence = "" if evidence in ("", "-", "—") else evidence
        if status == "done":
            if not evidence:
                errors.append(f"{item}: done without evidence (line {n})")
            elif not EVIDENCE.search(evidence):
                errors.append(f"{item}: evidence is not a link or owner line (line {n})")
            else:
                counts["done"] += 1
        elif status == "open":
            counts["open"] += 1
            open_items.append(f"open: {item}")
        elif status.startswith("waived"):
            if WAIVED.match(status):
                counts["waived"] += 1
            else:
                errors.append(f"{item}: waived needs '(<reason>, owner <date>)' (line {n})")
        else:
            errors.append(f"{item}: status must be done, open or waived (line {n})")


def check_extras(root, open_items):
    proto = root / "docs/protocol/README.md"
    if proto.is_file() and "TODO(plan 7)" in proto.read_text(encoding="utf-8"):
        open_items.append("open: docs/protocol/README.md still contains TODO(plan 7)")
    tm = root / "docs/security/threat-model.md"
    log = ""
    if tm.is_file():
        m = re.search(r"^##\s+\d*\.?\s*Review log\s*$(.*?)(?=^## |\Z)",
                      tm.read_text(encoding="utf-8"), re.S | re.M | re.I)
        log = m.group(1) if m else ""
    if not OWNER_LINE.search(log):
        open_items.append("open: threat model review log has no owner line (👤 <date> <initials>)")
    legal = root / "docs/legal"
    for f in sorted(legal.glob("*.md")) if legal.is_dir() else []:
        lines = f.read_text(encoding="utf-8").splitlines()
        idx = next((i for i, l in enumerate(lines) if l.strip() == BANNER), None)
        if idx is None:
            continue
        nxt = [l for l in lines[idx + 1: idx + 6] if l.strip()][:1]
        if not (nxt and LAWYER_LINE.search(nxt[0])):
            open_items.append(f"open: docs/legal/{f.name} has no 'lawyer reviewed <date>' line under its banner")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--root", type=Path, default=ROOT)
    a = ap.parse_args(argv)
    errors, open_items = [], []
    counts = {"done": 0, "open": 0, "waived": 0}
    check_rows(a.root / "docs/release/beta-gate.md", errors, open_items, counts)
    check_extras(a.root, open_items)
    for e in errors:
        print(f"error: {e}")
    for o in open_items:
        print(o)
    print(f"beta gate: {counts['done']} done, {counts['open']} open, "
          f"{counts['waived']} waived, {len(errors)} errors, {len(open_items)} open items")
    if errors:
        return 1
    if open_items and not a.report:
        print("gate not met")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
