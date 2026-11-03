import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile


class UnsignedIPATests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location(
            "check_unsigned_ipa", Path(__file__).with_name("check_unsigned_ipa.py"))
        self.checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.checker)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.ipa = Path(self.temp.name) / "App-unsigned.ipa"

    def package(self, platform="iPhoneOS", extension=True, executable=True, profile=False):
        with zipfile.ZipFile(self.ipa, "w") as archive:
            bundles = [("Payload/App.app", "com.test.phone", "App")]
            if extension:
                bundles.append(("Payload/App.app/PlugIns/NotificationService.appex",
                                "com.test.phone.nse", "NotificationService"))
            for path, bundle, binary in bundles:
                info = {"CFBundleIdentifier": bundle, "CFBundleExecutable": binary,
                        "CFBundleSupportedPlatforms": [platform]}
                if path.endswith(".appex"):
                    info["NSExtension"] = {"NSExtensionPointIdentifier": "com.apple.usernotifications.service"}
                archive.writestr(path + "/Info.plist", plistlib.dumps(info))
                if executable:
                    archive.writestr(path + "/" + binary, b"fixture executable")
                if profile:
                    archive.writestr(path + "/embedded.mobileprovision", b"fixture profile")

    def test_device_app_and_notification_extension(self):
        self.package()
        self.assertEqual(self.checker.check(self.ipa), "com.test.phone")

    def test_rejects_simulator_build(self):
        self.package(platform="iPhoneSimulator")
        with self.assertRaisesRegex(ValueError, "iPhoneOS"):
            self.checker.check(self.ipa)

    def test_rejects_missing_extension(self):
        self.package(extension=False)
        with self.assertRaisesRegex(ValueError, "notification extension"):
            self.checker.check(self.ipa)

    def test_rejects_missing_executable(self):
        self.package(executable=False)
        with self.assertRaisesRegex(ValueError, "executable"):
            self.checker.check(self.ipa)

    def test_rejects_embedded_provisioning_profile(self):
        self.package(profile=True)
        with self.assertRaisesRegex(ValueError, "provisioning profile"):
            self.checker.check(self.ipa)


if __name__ == "__main__":
    unittest.main()
