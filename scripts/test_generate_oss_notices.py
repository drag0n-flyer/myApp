"""Tests for generate-oss-notices.py. Standard library only.

Run from the repository root:
    python3 -m unittest scripts/test_generate_oss_notices.py
"""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

GENERATOR = Path(__file__).resolve().parent / "generate-oss-notices.py"


def load():
    spec = importlib.util.spec_from_file_location("generate_oss_notices", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def pkg(name, version="1.0.0", license="MIT", manifest="/nonexistent/Cargo.toml", **extra):
    return {"id": f"{name} {version}", "name": name, "version": version, "license": license,
            "manifest_path": manifest, "source": "registry+x", "repository": None,
            "targets": [{"kind": ["lib"]}], **extra}


def node(pkg_id, deps=()):
    return {"id": pkg_id, "deps": [
        {"pkg": d, "dep_kinds": [{"kind": k, "target": None}]} for d, k in deps]}


class Reachable(unittest.TestCase):
    def test_only_normal_dependencies_are_kept(self):
        root = pkg("app")
        root["source"] = None
        metadata = {
            "packages": [root, pkg("normal"), pkg("buildtool"), pkg("devtool"), pkg("deep")],
            "resolve": {"root": "app 1.0.0", "nodes": [
                node("app 1.0.0", [("normal 1.0.0", None), ("buildtool 1.0.0", "build"),
                                   ("devtool 1.0.0", "dev")]),
                node("normal 1.0.0", [("deep 1.0.0", None)]),
                node("buildtool 1.0.0"), node("devtool 1.0.0"), node("deep 1.0.0"),
            ]},
        }
        names = [p["name"] for p in load().normal_packages(metadata)]
        self.assertEqual(sorted(names), ["deep", "normal"])

    def test_dependency_that_is_normal_and_build_is_kept(self):
        root = pkg("app")
        root["source"] = None
        both = {"id": "app 1.0.0", "deps": [{"pkg": "x 1.0.0", "dep_kinds": [
            {"kind": "build", "target": None}, {"kind": None, "target": None}]}]}
        metadata = {"packages": [root, pkg("x")],
                    "resolve": {"root": "app 1.0.0", "nodes": [both, node("x 1.0.0")]}}
        self.assertEqual([p["name"] for p in load().normal_packages(metadata)], ["x"])


class LicenseIds(unittest.TestCase):
    def test_ids_from_expression(self):
        ids = load().license_ids("(MIT OR Apache-2.0) AND Unicode-3.0")
        self.assertEqual(ids, ["MIT", "Apache-2.0", "Unicode-3.0"])

    def test_exception_and_slash(self):
        mod = load()
        self.assertEqual(mod.license_ids("Apache-2.0 WITH LLVM-exception OR MIT"), ["Apache-2.0", "MIT"])
        self.assertEqual(mod.license_ids("MIT/Apache-2.0"), ["MIT", "Apache-2.0"])


class CrateEntry(unittest.TestCase):
    def test_reads_license_files_next_to_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "LICENSE-MIT").write_text("MIT text with Copyright (c) Someone")
            (Path(d) / "README.md").write_text("not a license")
            entry = load().crate_entry(pkg("c", manifest=str(Path(d) / "Cargo.toml"), authors=["A <a@x>"]))
        self.assertEqual(entry["own_texts"], ["MIT text with Copyright (c) Someone"])
        self.assertEqual(entry["authors"], "A <a@x>")

    def test_no_file_means_no_own_text(self):
        entry = load().crate_entry(pkg("c", repository="https://example.org/c"))
        self.assertEqual(entry["own_texts"], [])
        self.assertEqual(entry["repository"], "https://example.org/c")


class Book(unittest.TestCase):
    canonical = {"MIT": "The MIT text.", "Apache-2.0": "The Apache text."}

    def entry(self, name, license="MIT", own=()):
        return {"name": name, "version": "1", "license": license, "authors": "", "repository": None,
                "own_texts": list(own), "pending": False}

    def test_components_reference_canonical_ids(self):
        book = load().build_book([self.entry("a", "MIT OR Apache-2.0")], self.canonical)
        self.assertEqual(book["components"][0]["textIds"], ["MIT", "Apache-2.0"])
        self.assertEqual(book["texts"]["MIT"], "The MIT text.")

    def test_unknown_license_has_no_canonical_id(self):
        book = load().build_book([self.entry("a", "MIT OR LGPL-2.1-or-later")], self.canonical)
        self.assertEqual(book["components"][0]["textIds"], ["MIT"])

    def test_identical_own_texts_are_stored_once(self):
        entries = [self.entry("a", own=["Copyright X"]), self.entry("b", own=["Copyright X"])]
        book = load().build_book(entries, self.canonical)
        a, b = book["components"]
        self.assertEqual(a["textIds"], b["textIds"])
        self.assertEqual(len(book["texts"]), 2)

    def test_own_text_equal_to_canonical_is_dropped(self):
        book = load().build_book([self.entry("a", own=["The  MIT\ntext."])], self.canonical)
        self.assertEqual(book["components"][0]["textIds"], ["MIT"])

    def test_every_text_id_resolves(self):
        book = load().build_book([self.entry("a", own=["Copyright X"])], self.canonical)
        for c in book["components"]:
            for t in c["textIds"]:
                self.assertIn(t, book["texts"])


class Build(unittest.TestCase):
    def test_vendored_and_pinned_entries(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "tools/webrtc").mkdir(parents=True)
            (root / "tools/webrtc/VERSION").write_text(
                "WEBRTC_MILESTONE=156\nOPUS_VERSION=1.6\nRELEASE=1\n")
            rn = root / "tools/call-lab/Packages/RNNoiseLab"
            rn.mkdir(parents=True)
            (rn / "COPYING").write_text("rnnoise license")
            (rn / "VENDOR.md").write_text("- Pinned commit: `70f1d256acd4b34a572f999a05c87bf00b67730d` (2025)\n")
            entries = load().vendored_entries(root)
        by_name = {e["name"]: e for e in entries}
        self.assertEqual(by_name["RNNoise"]["license"], "BSD-3-Clause")
        self.assertEqual(by_name["RNNoise"]["version"], "70f1d25")
        self.assertEqual(by_name["RNNoise"]["own_texts"], ["rnnoise license"])
        self.assertFalse(by_name["RNNoise"]["pending"])
        self.assertEqual(by_name["WebRTC"]["version"], "m156")
        self.assertTrue(by_name["WebRTC"]["pending"])
        self.assertEqual(by_name["Opus"]["version"], "1.6")
        self.assertTrue(by_name["Opus"]["pending"])

    def test_book_is_sorted_and_has_texts(self):
        mod = load()
        e = lambda n: {"name": n, "version": "1", "license": "MIT", "authors": "", "repository": None,
                       "own_texts": [], "pending": False}
        data = json.loads(mod.notices_json([e("b"), e("A")], {"MIT": "t"}))
        self.assertEqual([c["name"] for c in data["components"]], ["A", "b"])
        self.assertEqual(data["texts"], {"MIT": "t"})
        self.assertEqual(set(data["components"][0]),
                         {"name", "version", "license", "authors", "repository", "textIds", "pending"})


if __name__ == "__main__":
    unittest.main()
