"""Run native instrumentation on an explicitly chosen emulator; fail on JUnit failures.

Build both APKs first. No server credentials are accepted: androidTest owns its fixtures.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Task-owned emulator serial, e.g. emulator-5580")
    parser.add_argument("--adb", help="Path to adb, otherwise ANDROID_HOME or PATH")
    parser.add_argument("--class", dest="test_class", help="Optional fully qualified class or class#method")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This fixture runner is restricted to explicitly chosen emulators, not personal devices.")
    root = Path(__file__).resolve().parents[1]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = args.adb or (str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else shutil.which("adb"))
    if not adb:
        parser.error("Set ANDROID_HOME or pass --adb.")
    prefix = [adb, "-s", args.serial]
    device = subprocess.run(prefix + ["get-state"], capture_output=True, text=True, check=True)
    if device.stdout.strip() != "device":
        parser.error("The selected emulator is not ready.")
    apks = [root / "android/app/build/outputs/apk/debug/app-debug.apk", root / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    for apk in apks:
        if not apk.is_file():
            parser.error(f"Build the missing APK first: {apk}")
        subprocess.run(prefix + ["install", "-r", str(apk)], check=True)
    command = prefix + ["shell", "am", "instrument", "-w", "-r"]
    if args.test_class:
        command += ["-e", "class", args.test_class]
    command += ["com.everyonesoftware.audio.test/androidx.test.runner.AndroidJUnitRunner"]
    result = subprocess.run(command, capture_output=True, text=True)
    output = result.stdout + result.stderr
    print(output)
    match = re.search(r"\bOK \((\d+) tests?\)", output)
    failed = re.search(r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", output)
    passed = result.returncode == 0 and match is not None and failed is None
    directory = root / "test-results"
    directory.mkdir(exist_ok=True)
    (directory / "native-instrumentation.log").write_text(output, encoding="utf-8")
    (directory / "native-instrumentation.json").write_text(json.dumps({"passed": passed, "tests": int(match.group(1)) if match else None, "serial": args.serial, "class": args.test_class}, indent=2), encoding="utf-8")
    # am instrument can exit zero even when assertions fail: the OK summary is mandatory.
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
