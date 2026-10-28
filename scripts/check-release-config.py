#!/usr/bin/env python3
"""Check apps/apple/project.yml and the *.entitlements files for App Store requirements.

Every requirement is reported as PASS, MISSING or PENDING-OWNER. Exit 1 if anything is
MISSING, unless --allow-missing is given. Exit 2 if the project file cannot be read.

project.yml is parsed as YAML (never with grep, so a commented-out key does not count).
PyYAML is not in the standard library, so the parser is `ruby -ryaml`, which ships on
macOS runners. Without Ruby, the file must be JSON-compatible YAML (the test fixtures);
full-line `#` comments are dropped first, as YAML would.
"""
import argparse
import fnmatch
import glob
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys

PLACEHOLDER_BUNDLE = "org.example.app"
DEFAULT_APP_GROUP = "group.org.example.app"
FILTERING_KEY = "com.apple.developer.usernotifications.filtering"
RUBY_SNIPPET = "puts JSON.generate(YAML.safe_load(File.read(ARGV[0], encoding: 'UTF-8'), aliases: true))"
MANIFEST_NAME = "PrivacyInfo.xcprivacy"
API_CATEGORIES = {
    "NSPrivacyAccessedAPICategoryFileTimestamp",
    "NSPrivacyAccessedAPICategorySystemBootTime",
    "NSPrivacyAccessedAPICategoryDiskSpace",
    "NSPrivacyAccessedAPICategoryActiveKeyboards",
    "NSPrivacyAccessedAPICategoryUserDefaults",
}
IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".heic", ".pdf", ".svg")


def load_project(path):
    if shutil.which("ruby"):
        r = subprocess.run(
            ["ruby", "-ryaml", "-rjson", "-e", RUBY_SNIPPET, path],
            capture_output=True, text=True,
        )
        if r.returncode != 0:
            raise ValueError("ruby could not parse %s: %s" % (path, r.stderr.strip()))
        return json.loads(r.stdout)
    with open(path, encoding="utf-8") as f:
        text = "\n".join(l for l in f.read().splitlines() if not l.lstrip().startswith("#"))
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(
            "Ruby is not installed and %s is not JSON-compatible YAML (%s). "
            "Install ruby (preinstalled on macOS runners)." % (path, e)
        )


def resolve(value, settings, overrides=None):
    """Substitute $(NAME) build settings, innermost first. Unknown names stay as they are."""
    settings = dict(settings)
    settings.update(overrides or {})
    pattern = re.compile(r"\$\(([A-Za-z0-9_]+)\)")
    for _ in range(10):
        new = pattern.sub(lambda m: str(settings.get(m.group(1), m.group(0))), value)
        if new == value:
            break
        value = new
    return value


class Report:
    def __init__(self):
        self.rows = []

    def add(self, status, scope, text):
        self.rows.append((status, scope, text))
        print("%-13s %s: %s" % (status, scope, text))

    def check(self, ok, scope, text):
        self.add("PASS" if ok else "MISSING", scope, text)

    def missing(self):
        return [r for r in self.rows if r[0] == "MISSING"]


def info_value(target, key):
    """A target's Info.plist value: from info.properties or an INFOPLIST_KEY_ build setting."""
    props = (target.get("info") or {}).get("properties") or {}
    if key in props:
        return props[key]
    return ((target.get("settings") or {}).get("base") or {}).get("INFOPLIST_KEY_" + key)


def manifest_in_target(target, base_dir):
    """Path of the target's PrivacyInfo.xcprivacy if xcodegen would bundle it, else None.

    Bundled means: listed as its own source path, or inside a source directory without being excluded.
    """
    for src in target.get("sources") or []:
        sp = src.get("path") if isinstance(src, dict) else src
        if not sp:
            continue
        excludes = src.get("excludes") or [] if isinstance(src, dict) else []
        if os.path.basename(sp) == MANIFEST_NAME:
            full = os.path.join(base_dir, sp)
        else:
            full = os.path.join(base_dir, sp, MANIFEST_NAME)
            if any(fnmatch.fnmatch(MANIFEST_NAME, e) or os.path.basename(e) == MANIFEST_NAME
                   for e in excludes):
                continue
        if os.path.isfile(full):
            return full
    return None


def manifest_problem(path):
    """Why the manifest is not acceptable, or None."""
    try:
        with open(path, "rb") as f:
            m = plistlib.load(f)
    except (OSError, plistlib.InvalidFileException, ValueError) as e:
        return "not a valid plist (%s)" % e
    if not isinstance(m, dict):
        return "top level is not a dictionary"
    if m.get("NSPrivacyTracking") is not False:
        return "NSPrivacyTracking must be false"
    if m.get("NSPrivacyTrackingDomains") != []:
        return "NSPrivacyTrackingDomains must be an empty array"
    if not isinstance(m.get("NSPrivacyCollectedDataTypes"), list):
        return "NSPrivacyCollectedDataTypes must be an array"
    apis = m.get("NSPrivacyAccessedAPITypes")
    if not isinstance(apis, list):
        return "NSPrivacyAccessedAPITypes must be an array"
    for a in apis:
        if not isinstance(a, dict) or a.get("NSPrivacyAccessedAPIType") not in API_CATEGORIES:
            return "unknown NSPrivacyAccessedAPIType"
        reasons = a.get("NSPrivacyAccessedAPITypeReasons")
        if not isinstance(reasons, list) or not reasons or not all(nonempty(r) for r in reasons):
            return "%s has no reason codes" % a["NSPrivacyAccessedAPIType"]
    return None


def nonempty(v):
    return v is not None and str(v).strip() != ""


def truthy(v):
    return v is True or str(v).strip().upper() in ("YES", "TRUE")


def as_list(v):
    return v if isinstance(v, list) else []


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--project", default="apps/apple/project.yml")
    ap.add_argument("--allow-missing", action="store_true", help="exit 0 even if something is MISSING")
    ap.add_argument("--placeholder-brand-ok", action="store_true", help="accept the org.example.app bundle ID")
    ap.add_argument("--filtering-pending", action="store_true",
                    help="Apple has not granted the notification-filtering entitlement yet")
    ap.add_argument("--app-group", default=DEFAULT_APP_GROUP)
    args = ap.parse_args(argv)

    try:
        project = load_project(args.project)
    except (OSError, ValueError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    base_dir = os.path.dirname(os.path.abspath(args.project))
    proj_settings = (project.get("settings") or {}).get("base") or {}
    targets = project.get("targets") or {}

    ent_cache = {}

    def load_entitlements(rel):
        p = os.path.join(base_dir, rel)
        if p not in ent_cache:
            try:
                with open(p, "rb") as f:
                    ent_cache[p] = plistlib.load(f)
            except (OSError, plistlib.InvalidFileException, ValueError):
                ent_cache[p] = None
        return ent_cache[p]

    def entitlements(target, overrides=None):
        s = dict(proj_settings)
        s.update((target.get("settings") or {}).get("base") or {})
        rel = s.get("CODE_SIGN_ENTITLEMENTS")
        if not rel:
            return None
        return load_entitlements(resolve(rel, s, overrides))

    def find(platform, kind):
        for name, t in targets.items():
            if t.get("platform") == platform and t.get("type") == kind:
                return name, t
        return None, None

    # Every *.entitlements file next to the project must be a valid plist.
    for p in sorted(glob.glob(os.path.join(base_dir, "**", "*.entitlements"), recursive=True)):
        ent_cache.setdefault(p, load_entitlements(os.path.relpath(p, base_dir)))

    rep = Report()
    app_ents = {}

    for platform, label in (("iOS", "iOS"), ("macOS", "Mac")):
        name, app = find(platform, "application")
        scope = label + " app"
        if app is None:
            rep.add("MISSING", scope, "no application target for %s in project.yml" % platform)
            continue
        ent = entitlements(app) or None
        if ent is None:
            rep.add("MISSING", scope, "entitlements file (CODE_SIGN_ENTITLEMENTS) not found or unreadable")
            ent = {}
            ok_file = False
        else:
            ok_file = True
        app_ents[platform] = ent
        c = lambda ok, text: rep.check(ok and ok_file, scope, text)
        domains = as_list(ent.get("com.apple.developer.associated-domains"))
        c(any(d.startswith("applinks:") for d in domains), "associated domain applinks:")
        c(any(d.startswith("webcredentials:") for d in domains), "associated domain webcredentials:")
        c(args.app_group in as_list(ent.get("com.apple.security.application-groups")),
          "app group %s" % args.app_group)
        c(len(as_list(ent.get("keychain-access-groups"))) > 0, "keychain-access-groups")
        if platform == "iOS":
            c(nonempty(ent.get("aps-environment")), "aps-environment")
            modes = info_value(app, "UIBackgroundModes")
            modes = modes if isinstance(modes, list) else []
            for m in ("voip", "remote-notification", "audio"):
                rep.check(m in modes, scope, "UIBackgroundModes contains %s" % m)
            rep.check(truthy(info_value(app, "ITSAppUsesNonExemptEncryption")), scope,
                      "ITSAppUsesNonExemptEncryption = YES")
            rep.check(nonempty(info_value(app, "NSMicrophoneUsageDescription")), scope,
                      "NSMicrophoneUsageDescription is not empty")
            rep.check(nonempty(info_value(app, "NSCameraUsageDescription")), scope,
                      "NSCameraUsageDescription is not empty")
        else:
            c(nonempty(ent.get("com.apple.developer.aps-environment")), "com.apple.developer.aps-environment")
            c(ent.get("com.apple.security.app-sandbox") is True, "app sandbox")
            c(ent.get("com.apple.security.network.client") is True, "network client")
            c(ent.get("com.apple.security.device.audio-input") is True, "device audio input")
            c(ent.get("com.apple.security.device.camera") is True, "device camera")
            rep.check(truthy(info_value(app, "ITSAppUsesNonExemptEncryption")), scope,
                      "ITSAppUsesNonExemptEncryption = YES")

    for platform, label in (("iOS", "iOS"), ("macOS", "Mac")):
        name, nse = find(platform, "app-extension")
        scope = label + " NSE"
        if nse is None:
            rep.add("MISSING", scope, "no app-extension target for %s in project.yml" % platform)
            continue
        ent = entitlements(nse)
        if ent is None:
            rep.add("MISSING", scope, "entitlements file (CODE_SIGN_ENTITLEMENTS) not found or unreadable")
            ent = {}
            ok_file = False
        else:
            ok_file = True
        app = app_ents.get(platform, {})
        groups = as_list(ent.get("com.apple.security.application-groups"))
        kgroups = as_list(ent.get("keychain-access-groups"))
        rep.check(ok_file and bool(groups) and groups == as_list(app.get("com.apple.security.application-groups")),
                  scope, "app group matches the app's")
        rep.check(ok_file and bool(kgroups) and kgroups == as_list(app.get("keychain-access-groups")),
                  scope, "keychain group matches the app's")
        if platform == "iOS":
            text = FILTERING_KEY
            if args.filtering_pending:
                rep.add("PENDING-OWNER", scope, "%s (Apple has not granted it yet)" % text)
            else:
                fent = entitlements(nse, {"NSE_FILTERING": "YES"}) or {}
                rep.check(fent.get(FILTERING_KEY) is True, scope, text)

    for name, t in sorted(targets.items()):
        if t.get("type") not in ("application", "app-extension"):
            continue
        bid = ((t.get("settings") or {}).get("base") or {}).get("PRODUCT_BUNDLE_IDENTIFIER", "")
        placeholder = (not bid) or bid == PLACEHOLDER_BUNDLE or bid.startswith(PLACEHOLDER_BUNDLE + ".")
        if placeholder and args.placeholder_brand_ok and bid:
            rep.add("PASS", name, "bundle ID %s is the placeholder (allowed by --placeholder-brand-ok)" % bid)
        else:
            rep.check(not placeholder, name,
                      "real bundle ID (found %s)" % (bid or "none"))

    for name, t in sorted(targets.items()):
        if t.get("type") != "application":
            continue
        found = False
        for src in t.get("sources") or []:
            sp = src.get("path") if isinstance(src, dict) else src
            if not sp:
                continue
            root = os.path.join(base_dir, sp)
            for d in glob.glob(os.path.join(root, "**", "*.appiconset"), recursive=True):
                if any(f.lower().endswith(IMAGE_EXTS) for f in os.listdir(d)):
                    found = True
            for d in glob.glob(os.path.join(root, "**", "*.icon"), recursive=True):
                if os.path.isfile(os.path.join(d, "icon.json")):
                    found = True
        rep.check(found, name, "app icon set is not empty")

    for platform, label in (("iOS", "iOS"), ("macOS", "Mac")):
        for kind, suffix in (("application", "app"), ("app-extension", "NSE")):
            name, t = find(platform, kind)
            if t is None:
                continue  # already reported above
            scope = "%s %s" % (label, suffix)
            path = manifest_in_target(t, base_dir)
            if path is None:
                rep.add("MISSING", scope, "privacy manifest %s is not in the target's sources" % MANIFEST_NAME)
                continue
            problem = manifest_problem(path)
            if problem:
                rep.add("MISSING", scope, "privacy manifest: %s" % problem)
            else:
                rep.add("PASS", scope, "privacy manifest %s" % MANIFEST_NAME)

    miss = rep.missing()
    print("\nSummary: %d requirement(s) not met%s" % (len(miss), " (allowed by --allow-missing)" if miss and args.allow_missing else ""))
    return 1 if miss and not args.allow_missing else 0


if __name__ == "__main__":
    sys.exit(main())
