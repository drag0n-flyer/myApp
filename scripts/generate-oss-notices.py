#!/usr/bin/env python3
"""Build the open-source notices JSON shown on the app's licenses page.

Sources:
- Canonical license texts: scripts/license-texts/ (SPDX), stored once and referenced by id.
- Rust crates: `cargo metadata --locked` for mlsffi, normal (not build, not dev)
  dependencies only. Each crate keeps its own license file text (copyright lines) when it ships one,
  next to the canonical text of its license.
- Vendored components: LICENSE files in this repository (RNNoise).
- WebRTC and Opus: pinned entries from tools/webrtc/VERSION, marked pending until
  the first WebRTC release provides the license file.

- Android (with --android-output): the same crates and vendored components plus the Gradle
  runtime dependencies listed in --gradle-deps (written by `./gradlew :app:exportRuntimeDependencies`).
  Their licenses come from the POM files that task copies to apps/android/app/build/oss/poms. --check-android also fails on a
  Gradle library whose license is copyleft, or not allowed and not approved in
  docs/security/license-allowlist.md (section gradle-libraries).

Standard library only. Usage: generate-oss-notices.py [--manifest PATH] [--output PATH]
       [--gradle-deps FILE --android-output PATH [--check-android]]
"""

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "apps/apple/Packages/SecureMessaging/mlsffi/Cargo.toml"
DEFAULT_OUTPUT = REPO_ROOT / "apps/apple/Packages/AppCore/Sources/AppCore/Resources/oss-notices.json"
CANONICAL_DIR = REPO_ROOT / "scripts/license-texts"
LICENSE_FILE = re.compile(r"^(licen[sc]e|copying|unlicense)", re.IGNORECASE)
ALLOWLIST = REPO_ROOT / "docs/security/license-allowlist.md"
DEFAULT_GRADLE_DEPS = REPO_ROOT / "apps/android/app/build/oss/runtime-dependencies.txt"
DEFAULT_GRADLE_POMS = REPO_ROOT / "apps/android/app/build/oss/poms"
DEFAULT_ANDROID_OUTPUT = REPO_ROOT / "apps/android/app/src/main/assets/oss-notices.json"
ANDROID_SDK_TERMS = "https://developer.android.com/studio/terms.html"
# Libraries whose POM lists several licenses or none. JNA is dual-licensed (LGPL-2.1-or-later OR
# Apache-2.0); the owner counts it as Apache-2.0.
LICENSE_OVERRIDES = {("net.java.dev.jna", "jna"): "Apache-2.0"}
COPYLEFT = re.compile(r"(^|[^A-Za-z])(A|L)?GPL", re.IGNORECASE)
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


def license_ids(expression):
    """SPDX ids named in an expression, in order, without keywords or exceptions."""
    ids, skip_next = [], False
    for token in re.findall(r"[^\s()/]+", expression or ""):
        if skip_next:
            skip_next = False
        elif token == "WITH":
            skip_next = True
        elif token not in ("OR", "AND") and token not in ids:
            ids.append(token)
    return ids


def load_canonical(directory=CANONICAL_DIR):
    return {f.stem: f.read_text(encoding="utf-8").strip()
            for f in sorted(Path(directory).glob("*.txt")) if f.name != "SOURCE.txt"}


def crate_entry(package):
    crate_dir = Path(package["manifest_path"]).parent
    if not crate_dir.is_dir():
        sys.exit(f"generate-oss-notices: source of {package['name']} {package['version']} not found at "
                 f"{crate_dir}; run `cargo fetch --locked` in mlsffi first")
    files = sorted(f for f in crate_dir.iterdir() if f.is_file() and LICENSE_FILE.match(f.name))
    return {"name": package["name"], "version": package["version"],
            "license": package.get("license") or "unknown",
            "authors": ", ".join(package.get("authors") or []),
            "repository": package.get("repository"),
            "own_texts": [f.read_text(encoding="utf-8", errors="replace").strip() for f in files],
            "pending": False}


def _normalized(text):
    return " ".join(text.split())


def build_book(entries, canonical):
    """Components point at text ids; each distinct text is stored once."""
    texts, components = {}, []
    canonical_norm = {_normalized(t) for t in canonical.values()}
    for e in sorted(entries, key=lambda e: (e["name"].lower(), e["version"])):
        ids = [i for i in license_ids(e["license"]) if i in canonical]
        for i in ids:
            texts[i] = canonical[i]
        for own in e["own_texts"]:
            if not own or _normalized(own) in canonical_norm:
                continue
            text_id = "sha-" + hashlib.sha256(own.encode("utf-8")).hexdigest()[:12]
            texts[text_id] = own
            if text_id not in ids:
                ids.append(text_id)
        components.append({"name": e["name"], "version": e["version"], "license": e["license"],
                           "authors": e["authors"], "repository": e["repository"],
                           "textIds": ids, "pending": e["pending"]})
    return {"texts": dict(sorted(texts.items())), "components": components}


def read_version_file(path):
    values = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep:
            values[key.strip()] = value.strip()
    return values


def vendored_entries(root):
    version = read_version_file(root / "tools/webrtc/VERSION")
    # TODO: when calling/lane-m3 lands on main, read RNNoise from the copy the app ships,
    # apps/apple/Packages/CallMedia/Sources/RNNoise/LICENSE, not the call-lab copy.
    rnnoise_dir = root / "tools/call-lab/Packages/RNNoiseLab"
    commit = re.search(r"Pinned commit: `([0-9a-f]{7,40})`", (rnnoise_dir / "VENDOR.md").read_text())

    def entry(name, version, own, pending, authors=""):
        return {"name": name, "version": version, "license": "BSD-3-Clause", "authors": authors,
                "repository": None, "own_texts": [own], "pending": pending}

    pending = PENDING_TEXT.format(license="BSD-3-Clause")
    return [
        entry("RNNoise", commit.group(1)[:7] if commit else "unknown",
              (rnnoise_dir / "COPYING").read_text().strip(), False, "Xiph.Org Foundation, Mozilla, Amazon, Jean-Marc Valin"),
        entry("WebRTC", f"m{version['WEBRTC_MILESTONE']}", pending, True, "The WebRTC project authors"),
        entry("Opus", version["OPUS_VERSION"], pending, True, "Xiph.Org Foundation and contributors"),
    ]


def read_dependencies(path):
    """(group, name, version) per line of the file `exportRuntimeDependencies` writes."""
    return [tuple(line.strip().split(":")) for line in Path(path).read_text().splitlines() if line.strip()]


def _pom_path(cache, group, name, version):
    base = Path(cache) / group / name / version
    return next(iter(sorted(base.glob("*/*.pom"))), None)


def _pom_root(path):
    root = ET.parse(path).getroot()
    for el in root.iter():
        el.tag = el.tag.rpartition("}")[2]
    return root


def pom_licenses(cache, group, name, version, depth=0):
    """(name, url) licenses of a POM, inherited from the parent POM when it declares none."""
    path = _pom_path(cache, group, name, version)
    if path is None:
        sys.exit(f"generate-oss-notices: no POM for {group}:{name}:{version} in {cache}; "
                 "run `./gradlew :app:exportRuntimeDependencies` first")
    root = _pom_root(path)
    found = [(l.findtext("name") or "", l.findtext("url") or "") for l in root.findall("licenses/license")]
    parent = root.find("parent")
    if not found and parent is not None and depth < 8:
        return pom_licenses(cache, parent.findtext("groupId"), parent.findtext("artifactId"),
                            parent.findtext("version"), depth + 1)
    return found, (root.findtext("url") or None)


def spdx_from_pom(name, url):
    text = f"{name} {url}".lower()
    if "android software development kit" in text:
        return "LicenseRef-Android-SDK"
    if "zetetic" in text:
        return "LicenseRef-SQLCipher-Community"
    if "apache" in text:
        return "Apache-2.0"
    if re.search(r"bsd[-_ ]?3", text):
        return "BSD-3-Clause"
    if re.search(r"\bmit\b", text):
        return "MIT"
    return re.sub(r"\s+", "-", name.strip()) or "unknown"


def gradle_entry(cache, group, name, version):
    licenses, url = pom_licenses(cache, group, name, version)
    ids = []
    for lic_name, lic_url in licenses:
        spdx = spdx_from_pom(lic_name, lic_url)
        if spdx not in ids:
            ids.append(spdx)
    expression = LICENSE_OVERRIDES.get((group, name)) or " AND ".join(ids) or "unknown"
    own, pending = [], False
    if expression == "LicenseRef-Android-SDK":
        own = [f"Provided under the Android Software Development Kit License: {ANDROID_SDK_TERMS}"]
    elif expression == "LicenseRef-SQLCipher-Community":
        own, pending = [PENDING_TEXT.format(license="the SQLCipher Community Edition license")], True
    return {"name": f"{group}:{name}", "version": version, "license": expression, "authors": "",
            "repository": url, "own_texts": own, "pending": pending}


def gradle_entries(dependencies, cache):
    return [gradle_entry(cache, g, n, v) for g, n, v in dependencies]


def read_gradle_exceptions(allowlist_text):
    """Rows (library, approved-by) of the `gradle-libraries` table; library may end in `*`."""
    rows, inside = [], False
    for line in allowlist_text.splitlines():
        if line.startswith("## "):
            inside = line[3:].strip() == "gradle-libraries"
        elif inside and line.startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) >= 3 and cells[0] not in ("library",) and not set(cells[0]) <= set("-: "):
                rows.append((cells[0], cells[2]))
    return rows


def _rust_checker():
    spec = importlib.util.spec_from_file_location("check_rust_licenses", REPO_ROOT / "scripts/check-rust-licenses.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_android(entries, exceptions):
    """Problems with Gradle libraries: copyleft, or not allowed and not approved in the allowlist."""
    allowed = _rust_checker().expression_allowed
    problems = []
    for e in entries:
        if COPYLEFT.search(e["license"]):
            problems.append(f"{e['name']} {e['version']}: copyleft license {e['license']}")
            continue
        if allowed(e["license"]):
            continue
        row = next((r for r in exceptions
                    if r[0] == e["name"] or (r[0].endswith("*") and e["name"].startswith(r[0][:-1]))), None)
        if row is None:
            problems.append(f"{e['name']} {e['version']}: license {e['license']} is not on the allowed list "
                            "and the library is not approved in docs/security/license-allowlist.md (gradle-libraries)")
        elif row[1].strip().lower() in ("", "pending"):
            problems.append(f"{e['name']} {e['version']}: approval in the allowlist is still pending")
    return problems


def check_pending_notices(entries):
    """Problems for every notice whose text is still a placeholder; a release must not ship one."""
    return [f"{e['name']} {e['version']}: notice text is still a placeholder (pending); a release cannot ship it"
            for e in entries if e.get("pending")]


def crate_entries(metadata):
    return [crate_entry(p) for p in normal_packages(metadata)]


def notices_json(entries, canonical):
    return json.dumps(build_book(entries, canonical), indent=1, ensure_ascii=False) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--output", help=f"Apple notices (default {DEFAULT_OUTPUT.relative_to(REPO_ROOT)}; skipped when only Android output is requested)")
    parser.add_argument("--gradle-deps", default=str(DEFAULT_GRADLE_DEPS))
    parser.add_argument("--gradle-cache", default=str(DEFAULT_GRADLE_POMS))
    parser.add_argument("--android-output", help="also write the Android notices (adds the Gradle libraries)")
    parser.add_argument("--release", action="store_true",
                        help="with --check-android: a pending notice fails the check (tagged release); "
                             "without it, pending notices only print a WARNING")
    parser.add_argument("--check-android", action="store_true",
                        help="fail on a Gradle library with a copyleft or unapproved license")
    args = parser.parse_args()
    out = subprocess.run(
        ["cargo", "metadata", "--locked", "--format-version", "1", "--manifest-path", args.manifest],
        check=True, capture_output=True, text=True,
    ).stdout
    entries = crate_entries(json.loads(out)) + vendored_entries(REPO_ROOT)
    if args.android_output or args.check_android:
        gradle = gradle_entries(read_dependencies(args.gradle_deps), args.gradle_cache)
        if args.check_android:
            problems = check_android(gradle, read_gradle_exceptions(ALLOWLIST.read_text()))
            pending = check_pending_notices(entries + gradle)
            if args.release:
                problems += pending
            else:
                for p in pending:
                    print(f"generate-oss-notices: WARNING: {p}", file=sys.stderr)
            for p in problems:
                print(f"generate-oss-notices: {p}", file=sys.stderr)
            if problems:
                return 1
            print(f"{len(gradle)} Gradle libraries pass the license check")
        if args.android_output:
            Path(args.android_output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.android_output).write_text(notices_json(entries + gradle, load_canonical()), encoding="utf-8")
            print(f"wrote {len(entries) + len(gradle)} components to {args.android_output}")
    android_only = (args.android_output or args.check_android) and not args.output
    if android_only:
        return 0
    output = Path(args.output or DEFAULT_OUTPUT)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(notices_json(entries, load_canonical()), encoding="utf-8")
    print(f"wrote {len(entries)} components to {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
