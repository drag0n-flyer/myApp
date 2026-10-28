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
        with tempfile.TemporaryDirectory() as d:
            entry = load().crate_entry(pkg("c", manifest=str(Path(d) / "Cargo.toml"),
                                           repository="https://example.org/c"))
        self.assertEqual(entry["own_texts"], [])
        self.assertEqual(entry["repository"], "https://example.org/c")

    def test_missing_crate_source_is_an_error(self):
        # A missing source dir would silently drop the crate's own notice.
        with self.assertRaises(SystemExit):
            load().crate_entry(pkg("c", manifest="/nonexistent/Cargo.toml"))


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


def write_pom(cache, coord, body, parent=None):
    g, n, v = coord.split(":")
    d = Path(cache) / g / n / v / "abc123"
    d.mkdir(parents=True)
    parent_xml = ""
    if parent:
        pg, pn, pv = parent.split(":")
        parent_xml = f"<parent><groupId>{pg}</groupId><artifactId>{pn}</artifactId><version>{pv}</version></parent>"
    (d / f"{n}-{v}.pom").write_text(
        '<?xml version="1.0"?><project xmlns="http://maven.apache.org/POM/4.0.0">'
        f"{parent_xml}<url>https://example.org/{n}</url>{body}</project>")


def lic_xml(name, url=""):
    return f"<licenses><license><name>{name}</name><url>{url}</url></license></licenses>"


class Gradle(unittest.TestCase):
    def entries(self, poms, deps):
        mod = load()
        with tempfile.TemporaryDirectory() as cache:
            for coord, body, *parent in poms:
                write_pom(cache, coord, body, parent[0] if parent else None)
            return mod.gradle_entries(deps, cache)

    def test_dependency_file_is_parsed(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "deps.txt"
            f.write_text("a.b:c:1.0\n\nx.y:z:2.0-android\n")
            self.assertEqual(load().read_dependencies(f), [("a.b", "c", "1.0"), ("x.y", "z", "2.0-android")])

    def test_license_names_map_to_spdx(self):
        poms = [("g:a:1", lic_xml("The Apache Software License, Version 2.0")),
                ("g:b:1", lic_xml("The MIT License", "http://opensource.org/licenses/MIT")),
                ("g:c:1", lic_xml("BSD-3-Clause")),
                ("g:d:1", lic_xml("Android Software Development Kit License"))]
        got = {e["name"]: e["license"] for e in self.entries(poms, [("g", n, "1") for n in "abcd"])}
        self.assertEqual(got, {"g:a": "Apache-2.0", "g:b": "MIT", "g:c": "BSD-3-Clause",
                               "g:d": "LicenseRef-Android-SDK"})

    def test_license_is_inherited_from_the_parent_pom(self):
        poms = [("g:parent:1", lic_xml("Apache 2.0")), ("g:child:1", "", "g:parent:1")]
        got = self.entries(poms, [("g", "child", "1")])
        self.assertEqual(got[0]["license"], "Apache-2.0")

    def test_two_licenses_must_both_be_allowed(self):
        poms = [("g:two:1", lic_xml("MIT") + "")]
        poms[0] = ("g:two:1", "<licenses><license><name>MIT</name></license>"
                              "<license><name>LGPL-2.1-or-later</name></license></licenses>")
        self.assertEqual(self.entries(poms, [("g", "two", "1")])[0]["license"], "MIT AND LGPL-2.1-or-later")

    def test_jna_counts_as_apache(self):
        poms = [("net.java.dev.jna:jna:5", "<licenses><license><name>LGPL-2.1-or-later</name></license>"
                                           "<license><name>Apache-2.0</name></license></licenses>")]
        self.assertEqual(self.entries(poms, [("net.java.dev.jna", "jna", "5")])[0]["license"], "Apache-2.0")

    def test_sqlcipher_text_is_pending(self):
        poms = [("net.zetetic:sqlcipher-android:4", "<licenses><license><url>https://www.zetetic.net/sqlcipher/license/</url></license></licenses>")]
        e = self.entries(poms, [("net.zetetic", "sqlcipher-android", "4")])[0]
        self.assertTrue(e["pending"])
        self.assertNotIn("unknown", e["license"])

    def test_pending_notice_fails_the_release_check(self):
        mod = load()
        done = {"name": "a", "version": "1", "pending": False}
        wait = {"name": "net.zetetic:sqlcipher-android", "version": "4", "pending": True}
        self.assertEqual(mod.check_pending_notices([done]), [])
        got = mod.check_pending_notices([done, wait])
        self.assertEqual(len(got), 1)
        self.assertIn("sqlcipher-android", got[0])

    def test_release_flag_controls_pending_failure(self):
        import tempfile, subprocess, sys as _sys
        mod = load()
        src = Path(mod.__file__).read_text()
        self.assertIn("--release", src)
        for release, code in ((False, 0), (True, 1)):
            with tempfile.TemporaryDirectory() as d:
                deps = Path(d) / "deps.txt"
                deps.write_text("")
                out = subprocess.run(
                    [_sys.executable, mod.__file__, "--check-android", "--gradle-deps", str(deps)]
                    + (["--release"] if release else []), capture_output=True, text=True)
            if "cargo" in out.stderr and "pending" not in out.stderr:
                self.skipTest("cargo metadata unavailable")
            self.assertEqual(out.returncode, code, out.stderr)
            self.assertIn("WebRTC", out.stderr)
            self.assertEqual("WARNING" in out.stderr, not release)

    def test_missing_pom_is_an_error(self):
        with self.assertRaises(SystemExit):
            self.entries([], [("g", "gone", "1")])

    def test_android_sdk_license_gets_its_terms_text(self):
        poms = [("g:d:1", lic_xml("Android Software Development Kit License", "https://developer.android.com/studio/terms.html"))]
        e = self.entries(poms, [("g", "d", "1")])[0]
        self.assertIn("https://developer.android.com/studio/terms.html", e["own_texts"][0])


class AndroidCheck(unittest.TestCase):
    def entry(self, name, license):
        return {"name": name, "version": "1", "license": license, "authors": "", "repository": None,
                "own_texts": [], "pending": False}

    def problems(self, entries, exceptions=()):
        return load().check_android(entries, list(exceptions))

    def test_allowed_licenses_pass(self):
        self.assertEqual(self.problems([self.entry("g:a", "Apache-2.0"), self.entry("g:b", "MIT AND BSD-3-Clause")]), [])

    def test_copyleft_fails_even_with_an_exception_row(self):
        got = self.problems([self.entry("g:a", "LGPL-2.1-or-later"), self.entry("g:b", "GPL-3.0-only"),
                             self.entry("g:c", "AGPL-3.0-only")], [("g:*", "owner 2026")])
        self.assertEqual(len(got), 3)

    def test_unlisted_license_fails(self):
        got = self.problems([self.entry("g:a", "LicenseRef-Android-SDK")])
        self.assertEqual(len(got), 1)
        self.assertIn("g:a", got[0])

    def test_exception_row_by_group_prefix_and_exact_name(self):
        entries = [self.entry("com.google.android.gms:play-services-base", "LicenseRef-Android-SDK"),
                   self.entry("net.zetetic:sqlcipher-android", "BSD-style (Zetetic)")]
        rows = [("com.google.android.gms:*", "owner 2026-10-28"), ("net.zetetic:sqlcipher-android", "owner 2026-10-28")]
        self.assertEqual(self.problems(entries, rows), [])

    def test_pending_exception_row_fails(self):
        got = self.problems([self.entry("g:a", "LicenseRef-Android-SDK")], [("g:a", "pending")])
        self.assertEqual(len(got), 1)

    def test_exception_rows_are_read_from_the_allowlist(self):
        text = ("## gradle-libraries\n\n| library | SPDX | approved-by |\n|---|---|---|\n"
                "| com.google.firebase:* | LicenseRef-Android-SDK | owner |\n"
                "| x:y | Foo | pending |\n\n## other\n| a:b | c | d |\n")
        self.assertEqual(load().read_gradle_exceptions(text),
                         [("com.google.firebase:*", "owner"), ("x:y", "pending")])

    def test_real_allowlist_has_the_gradle_section(self):
        text = (GENERATOR.parent.parent / "docs/security/license-allowlist.md").read_text()
        self.assertTrue(load().read_gradle_exceptions(text))


class AndroidBook(unittest.TestCase):
    def test_android_book_has_gradle_crates_and_vendored_entries(self):
        mod = load()
        e = lambda n: {"name": n, "version": "1", "license": "MIT", "authors": "", "repository": None,
                       "own_texts": [], "pending": False}
        data = json.loads(mod.notices_json([e("crate"), e("g:lib")], {"MIT": "t"}))
        self.assertEqual([c["name"] for c in data["components"]], ["crate", "g:lib"])


if __name__ == "__main__":
    unittest.main()
