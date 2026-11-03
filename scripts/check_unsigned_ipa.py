#!/usr/bin/env python3
"""Validate the unsigned CI package before uploading or signing it."""
from pathlib import PurePosixPath
import plistlib
import sys
import zipfile


def check(path):
    with zipfile.ZipFile(path) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f"Corrupt ZIP entry: {bad}")
        names = set(archive.namelist())
        if any(name.endswith("/embedded.mobileprovision") for name in names):
            raise ValueError("Unsigned artifact contains a provisioning profile")
        if any("/_CodeSignature/" in name for name in names):
            raise ValueError("Unsigned artifact contains a bundle signature")
        main = "Payload/App.app/Info.plist"
        extension = "Payload/App.app/PlugIns/NotificationService.appex/Info.plist"
        if extension not in names:
            raise ValueError("Missing notification extension")
        infos = []
        for name in (main, extension):
            if name not in names:
                raise ValueError(f"Missing {name}")
            info = plistlib.loads(archive.read(name))
            if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
                raise ValueError(f"Expected an iPhoneOS device build: {name}")
            executable = info.get("CFBundleExecutable", "")
            binary = str(PurePosixPath(name).parent / executable)
            if not executable or binary not in names or not archive.getinfo(binary).file_size:
                raise ValueError(f"Missing executable: {name}")
            infos.append(info)
        bundle_id = infos[0].get("CFBundleIdentifier")
        if not bundle_id or infos[1].get("CFBundleIdentifier") != bundle_id + ".nse":
            raise ValueError("App and notification extension bundle IDs do not match")
        if infos[1].get("NSExtension", {}).get("NSExtensionPointIdentifier") != "com.apple.usernotifications.service":
            raise ValueError("Incorrect notification extension point")
        return bundle_id


if __name__ == "__main__":
    try:
        bundle_id = check(sys.argv[1])
        print(f"Unsigned iPhone package verified: {bundle_id} (app and notification extension)")
    except (IndexError, KeyError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
