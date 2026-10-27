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


class LicenseText(unittest.TestCase):
    def test_reads_license_files_next_to_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "LICENSE-MIT").write_text("MIT text")
            (Path(d) / "LICENSE-APACHE").write_text("Apache text")
            (Path(d) / "README.md").write_text("not a license")
            text = load().crate_license_text(pkg("c", manifest=str(Path(d) / "Cargo.toml")))
        self.assertIn("MIT text", text)
        self.assertIn("Apache text", text)
        self.assertNotIn("not a license", text)

    def test_fallback_when_no_file(self):
        p = pkg("c", license="MIT OR Apache-2.0", repository="https://example.org/c")
        text = load().crate_license_text(p)
        self.assertIn("MIT OR Apache-2.0", text)
        self.assertIn("https://example.org/c", text)


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
        self.assertEqual(by_name["RNNoise"]["text"], "rnnoise license")
        self.assertFalse(by_name["RNNoise"]["pending"])
        self.assertEqual(by_name["WebRTC"]["version"], "m156")
        self.assertTrue(by_name["WebRTC"]["pending"])
        self.assertEqual(by_name["Opus"]["version"], "1.6")
        self.assertTrue(by_name["Opus"]["pending"])

    def test_notices_are_sorted_and_have_the_four_fields(self):
        mod = load()
        out = mod.notices_json([
            {"name": "b", "version": "1", "license": "MIT", "text": "t", "pending": False},
            {"name": "A", "version": "1", "license": "MIT", "text": "t", "pending": False},
        ])
        data = json.loads(out)
        self.assertEqual([c["name"] for c in data["components"]], ["A", "b"])
        self.assertEqual(set(data["components"][0]), {"name", "version", "license", "text", "pending"})


if __name__ == "__main__":
    unittest.main()
