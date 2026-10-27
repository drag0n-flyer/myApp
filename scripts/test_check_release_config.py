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
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(".nse"),
                    "CODE_SIGN_ENTITLEMENTS":
                        "NotificationService/NSE-iOS$(NSE_FILTERING_$(NSE_FILTERING)_SUFFIX).entitlements",
                }},
            },
            "AppNSEMac": {
                "type": "app-extension", "platform": "macOS",
                "settings": {"base": {
                    "PRODUCT_BUNDLE_IDENTIFIER": ids(".mac.nse"),
                    "CODE_SIGN_ENTITLEMENTS": "NotificationService/NSE-Mac.entitlements",
                }},
            },
        },
    }


def make_fixture(root, proj=None, comment_lines=(), skip_icon=False):
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


if __name__ == "__main__":
    unittest.main()
