#!/usr/bin/env python3
"""Build and install on a physical iPhone. Run from the repository with make iphone."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
APPLE = ROOT / "apps/apple"


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True,
                          text=True, **kwargs)


def select_phone(devices, requested):
    phones = [device for device in devices
              if device.get("platform") == "com.apple.platform.iphoneos"
              and not device.get("simulator", False)
              and device.get("available") is True
              and device.get("modelCode", "").startswith("iPhone")]
    if requested:
        phones = [device for device in phones if device.get("identifier") == requested]
    if not phones:
        detail = f" with UDID {requested}" if requested else ""
        raise RuntimeError(
            f"No available iPhone{detail}. Connect and unlock your iPhone, accept Trust "
            "This Computer, enable Developer Mode, and pair it in Xcode."
        )
    if len(phones) > 1:
        choices = "\n".join(f"  {p['name']}: {p['identifier']}" for p in phones)
        raise RuntimeError("Several iPhones are available. Choose one with "
                           f"make iphone IPHONE_DEVICE=<UDID>:\n{choices}")
    return phones[0]


def main():
    try:
        run(["xcodebuild", "-version"], capture_output=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        raise RuntimeError(
            "Install Xcode 26 or later with iOS support and open it to finish setup. "
            "Then run: sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer"
        ) from None

    for command, instruction in (
        ("xcodegen", "Install XcodeGen: brew install xcodegen"),
        ("rustup", "Install Rust with rustup to build the messaging library."),
        ("cargo", "Install Rust with rustup to build the messaging library."),
    ):
        if not shutil.which(command):
            raise RuntimeError(instruction)

    config = APPLE / "iPhone.local.xcconfig"
    team = os.environ.get("DEVELOPMENT_TEAM", "").strip()
    local_team = None
    if config.is_file():
        local_team = re.search(r"^\s*DEVELOPMENT_TEAM\s*=\s*([A-Za-z0-9]{10})\s*(?://.*)?$",
                               config.read_text(), re.MULTILINE)
    if not team and not local_team:
        raise RuntimeError(
            "Set DEVELOPMENT_TEAM in apps/apple/iPhone.local.xcconfig "
            "(copy iPhone.local.xcconfig.example), or run "
            "make iphone DEVELOPMENT_TEAM=<your-team-ID>. Sign in to that team in Xcode first. "
            "The app's Push Notifications capability requires a paid developer team. "
            "Set your unique APP_ID in apps/apple/AppID.xcconfig."
        )
    if team and not re.fullmatch(r"[A-Za-z0-9]{10}", team):
        raise RuntimeError("DEVELOPMENT_TEAM must be your 10-character Apple team ID.")

    print("Finding your iPhone…", flush=True)
    devices = json.loads(run(["xcrun", "xcdevice", "list"], capture_output=True).stdout)
    device = select_phone(devices, os.environ.get("IPHONE_DEVICE", "").strip())
    udid = device["identifier"]
    print(f"Building for {device['name']} ({udid})…", flush=True)

    # Always run the existing builder: Cargo reuses its cache, but changed Rust sources
    # must not silently leave an old framework (or an app with no messaging engine).
    package = APPLE / "Packages/SecureMessaging"
    run(["bash", package / "scripts/build-mlsffi.sh"])
    if not (package / "Artifacts/MLSFFI.xcframework").is_dir():
        raise RuntimeError("The messaging build did not produce MLSFFI.xcframework.")
    run(["xcodegen", "generate", "--spec", APPLE / "project.yml"])

    derived = APPLE / "DerivedData/iPhone"
    build = ["xcodebuild", "-project", APPLE / "App.xcodeproj", "-scheme", "AppiOS",
             "-configuration", "Debug", "-destination", f"platform=iOS,id={udid}",
             "-derivedDataPath", derived, "-allowProvisioningUpdates",
             "-allowProvisioningDeviceRegistration"]
    if config.is_file():
        build += ["-xcconfig", config]
    if team:
        build += [f"DEVELOPMENT_TEAM={team}"]
    # Apply the team to the app and extension. Their bundle/group IDs continue to
    # come from AppID.xcconfig, and Xcode manages provisioning and signing.
    build += ["CODE_SIGN_STYLE=Automatic", "build"]
    try:
        run(build)
    except subprocess.CalledProcessError:
        print("Build failed; nothing was installed. For signing errors, check your Xcode "
              "account, DEVELOPMENT_TEAM and APP_ID. See Xcode's error above.", file=sys.stderr)
        raise

    app = derived / "Build/Products/Debug-iphoneos/App.app"
    if not app.is_dir():
        raise RuntimeError(f"Build finished without the expected app: {app}")
    print(f"Installing on {device['name']}… Keep your iPhone unlocked.", flush=True)
    run(["xcrun", "devicectl", "device", "install", "app", "--device", udid, app])
    print(f"Installed on {device['name']}. Open the app on your iPhone.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # Commands with captured output (device discovery) still need useful errors.
        if error.stderr:
            print(error.stderr.strip(), file=sys.stderr)
        print(f"error: {error.cmd[0]} failed (exit {error.returncode}).", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
