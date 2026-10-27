"""Tests for check-swift-licenses.py. Standard library only.

Run from the repository root:
    python3 -m unittest scripts/test_check_swift_licenses.py
"""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check-swift-licenses.py"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_swift_licenses", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ALLOWLIST = """# License allowlist

## go-modules

| module | version | SPDX | approved-by |
|---|---|---|---|
| github.com/coder/websocket | v1.8.15 | ISC | owner (plan 2 §2) |

## swift-packages

| identity | version | SPDX | approved-by |
|---|---|---|---|
| swift-argument-parser | 1.5.0 | Apache-2.0 | owner (test) |
| webrtc | 1.0.0 | BSD-3-Clause | pending |
"""


def resolved(*pins):
    return json.dumps(
        {
            "pins": [
                {
                    "identity": identity,
                    "kind": "remoteSourceControl",
                    "location": f"https://example.com/{identity}.git",
                    "state": {"revision": "abc", "version": version},
                }
                for identity, version in pins
            ],
            "version": 2,
        }
    )


class CheckSwiftLicenses(unittest.TestCase):
    def setUp(self):
        self.mod = load_checker()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.allowlist = self.root / "license-allowlist.md"
        self.allowlist.write_text(ALLOWLIST)
        self.apps = self.root / "apps" / "apple"
        self.apps.mkdir(parents=True)

    def write_resolved(self, text, name="App"):
        d = self.apps / name
        d.mkdir(parents=True, exist_ok=True)
        (d / "Package.resolved").write_text(text)

    def run_check(self):
        return self.mod.check(self.apps, self.allowlist)

    def test_unlisted_package_fails(self):
        self.write_resolved(resolved(("mystery-lib", "2.0.0")))
        problems = self.run_check()
        self.assertEqual(len(problems), 1)
        self.assertIn("mystery-lib", problems[0])

    def test_version_mismatch_fails(self):
        self.write_resolved(resolved(("swift-argument-parser", "1.6.0")))
        problems = self.run_check()
        self.assertEqual(len(problems), 1)
        self.assertIn("1.6.0", problems[0])

    def test_listed_package_passes(self):
        self.write_resolved(resolved(("swift-argument-parser", "1.5.0")))
        self.assertEqual(self.run_check(), [])

    def test_no_resolved_files_passes(self):
        self.assertEqual(self.run_check(), [])

    def test_pending_package_fails(self):
        self.write_resolved(resolved(("webrtc", "1.0.0")))
        problems = self.run_check()
        self.assertEqual(len(problems), 1)
        self.assertIn("pending", problems[0])


if __name__ == "__main__":
    unittest.main()
