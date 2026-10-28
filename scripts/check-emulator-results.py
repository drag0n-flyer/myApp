#!/usr/bin/env python3
"""Fails unless every expected module ran tests on every managed device, with none skipped or failed.

Reads apps/android/<module>/build/outputs/androidTest-results/**/TEST-*.xml and prints a table.
Usage: check-emulator-results.py <android-dir> <module:min_tests>... (devices: pixel6Api29, pixel6Api36)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEVICES = ["pixel6Api29", "pixel6Api36"]


def main() -> int:
    root = Path(sys.argv[1])
    expected = {a.split(":")[0]: int(a.split(":")[1]) for a in sys.argv[2:]}
    counts: dict = {}
    for xml in root.glob("*/build/outputs/androidTest-results/**/TEST-*.xml"):
        module = xml.relative_to(root).parts[0]
        device = next((p for p in xml.parts if re.fullmatch(r"pixel6Api\d+", p)), None)
        suite = ET.parse(xml).getroot()
        c = counts.setdefault((module, device), dict(tests=0, failures=0, errors=0, skipped=0))
        for k in c:
            c[k] += int(suite.get(k, 0))
    bad = []
    print(f"{'module':10} {'device':12} tests failures errors skipped")
    for module, minimum in expected.items():
        for device in DEVICES:
            c = counts.get((module, device))
            if c is None:
                print(f"{module:10} {device:12} NO RESULTS")
                bad.append(f"{module} {device}: no results")
                continue
            print(f"{module:10} {device:12} {c['tests']:5} {c['failures']:8} {c['errors']:6} {c['skipped']:7}")
            if c["tests"] < minimum:
                bad.append(f"{module} {device}: {c['tests']} tests, expected at least {minimum}")
            if c["failures"] or c["errors"] or c["skipped"]:
                bad.append(f"{module} {device}: failures/errors/skipped present")
    for b in bad:
        print(f"::error::{b}")
    return 1 if bad else 0


sys.exit(main())
