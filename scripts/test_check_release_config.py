import json
import os
import plistlib
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-release-config.py")
GROUP = "group.org.example.app"
KEYCHAIN = "$(AppIdentifierPrefix)org.example.app.shared"


def write_plist(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        plistlib.dump(data, f)


def ios_app_ent():
    return {
        "aps-environment": "development",
        "com.apple.developer.associated-domains": [
            "webcredentials:$(PASSKEY_DOMAINS)",
            "applinks:$(DEFAULT_SERVER_HOST)",
        ],
        "com.apple.security.application-groups": [GROUP],
        "keychain-access-groups": [KEYCHAIN],
    }


def mac_app_ent():
    return {
        "com.apple.developer.aps-environment": "development",
        "com.apple.developer.associated-domains": [
            "webcredentials:$(PASSKEY_DOMAINS)",
            "applinks:$(DEFAULT_SERVER_HOST)",
        ],
        "com.apple.security.application-groups": [GROUP],
        "com.apple.security.app-sandbox": True,
        "com.apple.security.network.client": True,
        "com.apple.security.device.audio-input": True,
        "com.apple.security.device.camera": True,
        "keychain-access-groups": [KEYCHAIN],
    }


def nse_ent(extra=None):
    d = {
        "com.apple.security.application-groups": [GROUP],
        "keychain-access-groups": [KEYCHAIN],
    }
    d.update(extra or {})
    return d


def project(bundle_prefix="com.realbrand.chat", modes=("voip", "audio", "remote-notification")):
    def ids(suffix):
        return bundle_prefix + suffix
    ios_props = {"ITSAppUsesNonExemptEncryption": True}
    if modes is not None:
        ios_props["UIBackgroundModes"] = list(modes)
    return {
        "name": "App",
        "settings": {"base": {
            "NSE_FILTERING": "NO",
            "NSE_FILTERING_YES_SUFFIX": "-filtering",
            "NSE_FILTERING_NO_SUFFIX": "",
        }},
        "targets": {
            "AppiOS": {
                "type": "application", "platform": "iOS",
                "sources": [{"path": "App"}],
                "info": {"path": "Generated/i.plist", "properties": ios_props},
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(""),
                    "CODE_SIGN_ENTITLEMENTS": "App/AppiOS.entitlements",
                    "INFOPLIST_KEY_NSMicrophoneUsageDescription": "Calls.",
                    "INFOPLIST_KEY_NSCameraUsageDescription": "QR codes.",
                }},
            },
            "AppMac": {
                "type": "application", "platform": "macOS",
                "sources": [{"path": "App"}],
                "info": {"path": "Generated/m.plist", "properties": {"ITSAppUsesNonExemptEncryption": True}},
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(".mac"),
                    "CODE_SIGN_ENTITLEMENTS": "App/AppMac.entitlements",
                }},
            },
            "AppNSEiOS": {
                "type": "app-extension", "platform": "iOS",
                "sources": [{"path": "NotificationService"}],
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(".nse"),
                    "CODE_SIGN_ENTITLEMENTS":
                        "NotificationService/NSE-iOS$(NSE_FILTERING_$(NSE_FILTERING)_SUFFIX).entitlements",
                }},
            },
            "AppNSEMac": {
                "type": "app-extension", "platform": "macOS",
                "sources": [{"path": "NotificationService"}],
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(".mac.nse"),
                    "CODE_SIGN_ENTITLEMENTS": "NotificationService/NSE-Mac.entitlements",
                }},
            },
        },
    }


def manifest(**changes):
    d = {
        "NSPrivacyTracking": False,
        "NSPrivacyTrackingDomains": [],
        "NSPrivacyCollectedDataTypes": [{
            "NSPrivacyCollectedDataType": "NSPrivacyCollectedDataTypeEmailAddress",
            "NSPrivacyCollectedDataTypeLinked": True,
            "NSPrivacyCollectedDataTypeTracking": False,
            "NSPrivacyCollectedDataTypePurposes": ["NSPrivacyCollectedDataTypePurposeAppFunctionality"],
        }],
        "NSPrivacyAccessedAPITypes": [{
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPITypeReasons": ["CA92.1"],
        }],
    }
    d.update(changes)
    return d


def make_fixture(root, proj=None, comment_lines=(), skip_icon=False, skip_manifests=()):
    proj = proj or project()
    text = json.dumps(proj, indent=2)
    text = "\n".join(list(comment_lines) + [text]) + "\n"
    with open(os.path.join(root, "project.yml"), "w") as f:
        f.write(text)
    write_plist(os.path.join(root, "App/AppiOS.entitlements"), ios_app_ent())
    write_plist(os.path.join(root, "App/AppMac.entitlements"), mac_app_ent())
    filtering = {"com.apple.developer.usernotifications.filtering": True}
    write_plist(os.path.join(root, "NotificationService/NSE-iOS.entitlements"), nse_ent())
    write_plist(os.path.join(root, "NotificationService/NSE-iOS-filtering.entitlements"), nse_ent(filtering))
    write_plist(os.path.join(root, "NotificationService/NSE-Mac.entitlements"), nse_ent())
    for rel in ("App", "NotificationService"):
        if rel not in skip_manifests:
            write_plist(os.path.join(root, rel, "PrivacyInfo.xcprivacy"), manifest())
    icon = os.path.join(root, "App/Assets.xcassets/AppIcon.appiconset")
    os.makedirs(icon)
    with open(os.path.join(icon, "Contents.json"), "w") as f:
        json.dump({"images": [{"filename": "icon.png"}]}, f)
    if not skip_icon:
        with open(os.path.join(icon, "icon.png"), "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n")


def run(root, *flags):
    return subprocess.run(
        [sys.executable, SCRIPT, "--project", os.path.join(root, "project.yml"), *flags],
        capture_output=True, text=True,
    )


def use_app_id_xcconfig(root, app_id):
    """Rewrites the fixture the way apps/apple does it: every ID built from APP_ID in AppID.xcconfig."""
    with open(os.path.join(root, "AppID.xcconfig"), "w") as f:
        f.write("// The app ID.\nAPP_ID = %s\n" % app_id)
    proj = project(bundle_prefix="$(APP_ID)")
    proj["configFiles"] = {"Debug": "AppID.xcconfig", "Release": "AppID.xcconfig"}
    with open(os.path.join(root, "project.yml"), "w") as f:
        f.write(json.dumps(proj, indent=2) + "\n")
    for rel in ("App/AppiOS.entitlements", "App/AppMac.entitlements", "NotificationService/NSE-iOS.entitlements",
                "NotificationService/NSE-iOS-filtering.entitlements", "NotificationService/NSE-Mac.entitlements"):
        path = os.path.join(root, rel)
        with open(path, "rb") as f:
            ent = plistlib.load(f)
        ent["com.apple.security.application-groups"] = ["group.$(APP_ID)"]
        ent["keychain-access-groups"] = ["$(AppIdentifierPrefix)$(APP_ID).shared"]
        write_plist(path, ent)


class CheckReleaseConfigTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.addCleanup(self._tmp.cleanup)

    def test_complete_fixture_passes(self):
        make_fixture(self.root)
        r = run(self.root)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn("MISSING", r.stdout)
        self.assertIn("PASS", r.stdout)

    def test_commented_key_still_missing(self):
        proj = project(modes=None)
        make_fixture(self.root, proj, comment_lines=["# UIBackgroundModes: [voip, audio, remote-notification]"])
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING.*UIBackgroundModes")

    def test_missing_voip_mode(self):
        make_fixture(self.root, project(modes=("audio", "remote-notification")))
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING.*voip")

    def test_placeholder_brand_flagged(self):
        make_fixture(self.root, project(bundle_prefix="org.example.app"))
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING.*bundle ID")
        ok = run(self.root, "--placeholder-brand-ok")
        self.assertEqual(ok.returncode, 0, ok.stdout)

    def test_allow_missing_exit_zero(self):
        make_fixture(self.root, project(modes=("audio",)))
        self.assertEqual(run(self.root).returncode, 1)
        r = run(self.root, "--allow-missing")
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("MISSING", r.stdout)

    def test_empty_icon_set_missing(self):
        make_fixture(self.root, skip_icon=True)
        r = run(self.root)
        self.assertEqual(r.returncode, 1)
        self.assertRegex(r.stdout, r"MISSING.*app icon")

    def test_filtering_entitlement_pending_owner(self):
        make_fixture(self.root)
        os.remove(os.path.join(self.root, "NotificationService/NSE-iOS-filtering.entitlements"))
        write_plist(os.path.join(self.root, "NotificationService/NSE-iOS-filtering.entitlements"), nse_ent())
        r = run(self.root)
        self.assertEqual(r.returncode, 1)
        self.assertRegex(r.stdout, r"MISSING.*usernotifications.filtering")
        p = run(self.root, "--filtering-pending")
        self.assertEqual(p.returncode, 0, p.stdout)
        self.assertIn("PENDING-OWNER", p.stdout)

    def test_unknown_key_in_nse_group_mismatch(self):
        make_fixture(self.root)
        bad = nse_ent()
        bad["com.apple.security.application-groups"] = ["group.other"]
        write_plist(os.path.join(self.root, "NotificationService/NSE-Mac.entitlements"), bad)
        r = run(self.root)
        self.assertEqual(r.returncode, 1)
        self.assertRegex(r.stdout, r"MISSING.*Mac NSE.*app group")

    def test_privacy_manifests_pass(self):
        make_fixture(self.root)
        r = run(self.root)
        self.assertRegex(r.stdout, r"PASS\s+iOS app: privacy manifest")
        self.assertRegex(r.stdout, r"PASS\s+Mac NSE: privacy manifest")

    def test_app_manifest_missing(self):
        make_fixture(self.root, skip_manifests=("App",))
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")
        self.assertRegex(r.stdout, r"MISSING\s+Mac app: privacy manifest")

    def test_nse_manifest_missing(self):
        make_fixture(self.root, skip_manifests=("NotificationService",))
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING\s+iOS NSE: privacy manifest")

    def test_manifest_excluded_from_sources_missing(self):
        proj = project()
        proj["targets"]["AppMac"]["sources"] = [{"path": "App", "excludes": ["PrivacyInfo.xcprivacy"]}]
        make_fixture(self.root, proj)
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+Mac app: privacy manifest")
        self.assertRegex(r.stdout, r"PASS\s+iOS app: privacy manifest")

    def test_manifest_listed_as_resource_passes(self):
        proj = project()
        proj["targets"]["AppMac"]["sources"] = [
            {"path": "App", "excludes": ["PrivacyInfo.xcprivacy"]},
            {"path": "App/PrivacyInfo.xcprivacy", "buildPhase": "resources"},
        ]
        make_fixture(self.root, proj)
        r = run(self.root)
        self.assertRegex(r.stdout, r"PASS\s+Mac app: privacy manifest")

    def test_manifest_tracking_true_missing(self):
        make_fixture(self.root)
        write_plist(os.path.join(self.root, "App/PrivacyInfo.xcprivacy"), manifest(NSPrivacyTracking=True))
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")

    def test_manifest_tracking_domains_missing(self):
        make_fixture(self.root)
        write_plist(os.path.join(self.root, "NotificationService/PrivacyInfo.xcprivacy"),
                    manifest(NSPrivacyTrackingDomains=["tracker.example"]))
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+iOS NSE: privacy manifest")

    def test_manifest_unknown_api_category_missing(self):
        make_fixture(self.root)
        bad = manifest(NSPrivacyAccessedAPITypes=[{
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryBogus",
            "NSPrivacyAccessedAPITypeReasons": ["CA92.1"]}])
        write_plist(os.path.join(self.root, "App/PrivacyInfo.xcprivacy"), bad)
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")

    def test_manifest_empty_reasons_missing(self):
        make_fixture(self.root)
        bad = manifest(NSPrivacyAccessedAPITypes=[{
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPITypeReasons": []}])
        write_plist(os.path.join(self.root, "App/PrivacyInfo.xcprivacy"), bad)
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")

    def test_manifest_not_a_plist_missing(self):
        make_fixture(self.root)
        with open(os.path.join(self.root, "App/PrivacyInfo.xcprivacy"), "w") as f:
            f.write("not a plist")
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")

    def test_manifest_missing_required_key_missing(self):
        make_fixture(self.root)
        d = manifest()
        del d["NSPrivacyCollectedDataTypes"]
        write_plist(os.path.join(self.root, "App/PrivacyInfo.xcprivacy"), d)
        r = run(self.root)
        self.assertRegex(r.stdout, r"MISSING\s+iOS app: privacy manifest")


    def test_app_id_from_xcconfig(self):
        make_fixture(self.root)
        use_app_id_xcconfig(self.root, "com.realbrand.chat")
        r = run(self.root)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("app group group.com.realbrand.chat", r.stdout)
        self.assertIn("real bundle ID (found com.realbrand.chat.mac.nse)", r.stdout)

    def test_placeholder_app_id_in_xcconfig_flagged(self):
        make_fixture(self.root)
        use_app_id_xcconfig(self.root, "org.example.app")
        r = run(self.root)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertRegex(r.stdout, r"MISSING\s+AppiOS: real bundle ID \(found org.example.app\)")
        ok = run(self.root, "--placeholder-brand-ok")
        self.assertEqual(ok.returncode, 0, ok.stdout)


if __name__ == "__main__":
    unittest.main()
