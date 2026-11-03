"""Exercise the install command with fake Apple tools; no phone or signing keys needed."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).with_name("iphone.py")
MAKEFILE = SOURCE.parent.parent / "Makefile"


def phone(identifier="phone-one", **changes):
    device = dict(identifier=identifier, name="My iPhone", available=True,
                  simulator=False, platform="com.apple.platform.iphoneos",
                  modelCode="iPhone17,1")
    device.update(changes)
    return device


class IPhoneCommandTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="iphone test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "scripts").mkdir()
        shutil.copyfile(SOURCE, self.root / "scripts/iphone.py")
        shutil.copyfile(MAKEFILE, self.root / "Makefile")
        self.apple = self.root / "apps/apple"
        self.package = self.apple / "Packages/SecureMessaging"
        (self.package / "scripts").mkdir(parents=True)
        (self.package / "scripts/build-mlsffi.sh").write_text(
            '#!/bin/bash\nset -e\nmls-build\nmkdir -p "$(dirname "$0")/../Artifacts/MLSFFI.xcframework"\n'
        )
        self.bin = self.root / "fake-tools"
        self.bin.mkdir()
        fake = self.bin / "fake"
        fake.write_text(f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["TRACE"], "a") as f:
    f.write(json.dumps([name] + args) + "\\n")
stage = name
if name == "xcodebuild":
    stage = "xcode-version" if "-version" in args else "build"
if name == "xcrun":
    stage = "devices" if args[:2] == ["xcdevice", "list"] else "install"
if os.environ.get("FAIL_STAGE") == stage:
    print("simulated " + stage + " failure", file=sys.stderr)
    sys.exit(1)
if stage == "devices":
    print(os.environ["DEVICES"])
if stage == "build":
    app = pathlib.Path(args[args.index("-derivedDataPath") + 1]) / "Build/Products/Debug-iphoneos/App.app"
    app.mkdir(parents=True, exist_ok=True)
''')
        fake.chmod(0o755)
        for tool in ("xcodebuild", "xcrun", "xcodegen", "rustup", "cargo", "mls-build"):
            (self.bin / tool).symlink_to(fake)
        self.trace = self.root / "trace.jsonl"
        self.env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}",
                    "TRACE": str(self.trace), "DEVICES": json.dumps([phone()]),
                    "DEVELOPMENT_TEAM": "TESTTEAM12", "IPHONE_DEVICE": ""}
        self.env.pop("FAIL_STAGE", None)

    def run_command(self, **env):
        return subprocess.run(["make", "iphone"], cwd=self.root,
                              env={**self.env, **env}, text=True, capture_output=True)

    def calls(self):
        return [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_builds_real_device_app_then_installs_it(self):
        devices = [phone("sim", simulator=True), phone("ipad", modelCode="iPad16,1"),
                   phone("offline", available=False), phone()]
        result = self.run_command(DEVICES=json.dumps(devices))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        build = next(c for c in calls if c[0] == "xcodebuild" and "build" in c)
        self.assertIn("platform=iOS,id=phone-one", build)
        self.assertIn("AppiOS", build)
        self.assertIn("DEVELOPMENT_TEAM=TESTTEAM12", build)
        self.assertIn("-allowProvisioningUpdates", build)
        self.assertNotIn("CODE_SIGNING_ALLOWED=NO", build)
        self.assertLess(next(i for i, c in enumerate(calls) if c[0] == "mls-build"), calls.index(build))
        install = calls[-1]
        self.assertEqual(install[:6], ["xcrun", "devicectl", "device", "install", "app", "--device"])
        self.assertEqual(install[6], "phone-one")
        self.assertTrue(install[-1].endswith("Debug-iphoneos/App.app"))

    def test_refuses_to_guess_between_two_phones(self):
        result = self.run_command(DEVICES=json.dumps([phone(), phone("phone-two")]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IPHONE_DEVICE", result.stderr)
        self.assertFalse(any(c[0] == "mls-build" for c in self.calls()))

    def test_explicit_device_uses_udid_even_with_duplicate_names(self):
        result = self.run_command(DEVICES=json.dumps([phone(), phone("phone-two")]),
                                  IPHONE_DEVICE="phone-two")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[-1][6], "phone-two")

    def test_no_available_phone_explains_pairing(self):
        result = self.run_command(DEVICES=json.dumps([phone(available=False)]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Trust", result.stderr)
        self.assertFalse(any(c[0] == "mls-build" for c in self.calls()))

    def test_unknown_device_does_not_install_on_another_phone(self):
        result = self.run_command(IPHONE_DEVICE="not-connected")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not-connected", result.stderr)
        self.assertFalse(any(c[0] == "mls-build" for c in self.calls()))

    def test_missing_xcode_gives_setup_instructions(self):
        result = self.run_command(FAIL_STAGE="xcode-version")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("xcode-select", result.stderr)
        self.assertEqual(len(self.calls()), 1)

    def test_missing_signing_team_explains_local_config(self):
        result = self.run_command(DEVELOPMENT_TEAM="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("iPhone.local.xcconfig", result.stderr)
        self.assertFalse(any(c[0] == "mls-build" for c in self.calls()))

    def test_local_config_is_passed_to_both_app_and_extension_build(self):
        config = self.apple / "iPhone.local.xcconfig"
        config.write_text("DEVELOPMENT_TEAM = LOCALTEAM1\n")
        result = self.run_command(DEVELOPMENT_TEAM="")
        self.assertEqual(result.returncode, 0, result.stderr)
        build = next(c for c in self.calls() if c[0] == "xcodebuild" and "build" in c)
        self.assertEqual(build[build.index("-xcconfig") + 1], str(config))
        self.assertFalse(any(c.startswith("PRODUCT_BUNDLE_IDENTIFIER=") for c in build))

    def test_failure_at_each_stage_prevents_later_actions(self):
        for stage in ("devices", "mls-build", "xcodegen", "build", "install"):
            with self.subTest(stage=stage):
                self.trace.unlink(missing_ok=True)
                result = self.run_command(FAIL_STAGE=stage)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Installed", result.stdout)
                if stage != "install":
                    self.assertFalse(any("install" in c for c in self.calls()))


if __name__ == "__main__":
    unittest.main()
