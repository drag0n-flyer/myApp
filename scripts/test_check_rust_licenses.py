"""Tests for check-rust-licenses.py. Standard library only.

Run from the repository root:
    python3 -m unittest scripts/test_check_rust_licenses.py
"""

import importlib.util
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check-rust-licenses.py"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_rust_licenses", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RustLicenses(unittest.TestCase):
    def setUp(self):
        self.ok = load_checker().expression_allowed

    def test_single_allowed(self):
        self.assertTrue(self.ok("MIT"))

    def test_or_needs_one_allowed(self):
        self.assertTrue(self.ok("MIT OR Apache-2.0 OR LGPL-2.1-or-later"))

    def test_and_needs_all_allowed(self):
        self.assertFalse(self.ok("MIT AND GPL-3.0-only"))
        self.assertTrue(self.ok("(MIT OR Apache-2.0) AND Unicode-3.0"))

    def test_slash_means_or(self):
        self.assertTrue(self.ok("MIT/Apache-2.0"))

    def test_with_exception_uses_base_license(self):
        self.assertTrue(self.ok("Apache-2.0 WITH LLVM-exception"))

    def test_denied_and_unknown_fail(self):
        for expr in ("GPL-3.0-only", "AGPL-3.0-or-later", "LGPL-2.1-only", "BUSL-1.1", "SSPL-1.0", "Weird-1.0", ""):
            self.assertFalse(self.ok(expr), expr)


if __name__ == "__main__":
    unittest.main()
