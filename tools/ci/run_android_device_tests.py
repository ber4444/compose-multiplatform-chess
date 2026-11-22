#!/usr/bin/env python3
"""Own the CI emulator, with bounded commands and cleanup on SIGINT/SIGTERM."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def stop_group(process):
    """Stop descendants too, even if the parent already exited with pipes still open."""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run(*args, timeout=15, input=None, **kwargs):
    print("+ " + " ".join(args), flush=True)
    process = subprocess.Popen(args, start_new_session=True, **kwargs)
    try:
        stdout, _ = process.communicate(input=input, timeout=timeout)
        if process.returncode:
            raise subprocess.CalledProcessError(process.returncode, args)
        return stdout
    finally:
        stop_group(process)


def require_test_results(directory):
    # AGP can succeed after an APK install failure with zero tests. Check both suites.
    if not any(
        int(ET.parse(path).getroot().get("tests", "0")) > 0
        for path in directory.rglob("*.xml")
    ):
        raise RuntimeError(f"No device tests ran in {directory}")


def interrupted(signum, _frame):
    raise SystemExit(128 + signum)


def main():
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    os.environ.update(ANDROID_SERIAL="emulator-5554", EMULATOR_PORT="5554")
    sdk = Path(os.environ["ANDROID_HOME"])
    avd_home = Path(os.environ.setdefault("ANDROID_AVD_HOME", str(Path.home() / ".android/avd")))
    avd_home.mkdir(parents=True, exist_ok=True)
    log_path = Path("build/ci-emulator.log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    image = f"system-images;android-{os.getenv('CHESS_API_LEVEL', '35')};{os.getenv('CHESS_SYSTEM_IMAGE_TARGET', 'default')};x86_64"
    run("avdmanager", "create", "avd", "--force", "--name", "chess-ci",
        "--device", os.getenv("CHESS_AVD_PROFILE", "pixel_7"), "--package", image,
        stdin=subprocess.PIPE, input=b"no\n", timeout=60)

    emulator = None
    try:
        # File-backed stdio means a surviving native child cannot hold the Actions log pipes
        # open. A separate session lets cleanup kill just this emulator and its children.
        # Preserve gRPC without JWT for AGP 9.5 / espresso-device rotation.
        with log_path.open("w") as log:
            emulator = subprocess.Popen([
                str(sdk / "emulator/emulator"), "-avd", "chess-ci", "-port", "5554",
                "-no-snapshot", "-noaudio", "-no-boot-anim", "-gpu", "swiftshader_indirect",
                "-camera-back", "none", "-no-window", "-grpc", "8554", "-memory", "2048", "-cores", "2",
            ], stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
        deadline = time.monotonic() + 180
        while True:
            if emulator.poll() is not None:
                raise RuntimeError("Emulator exited before completing boot")
            try:
                if run("adb", "shell", "getprop", "sys.boot_completed", timeout=5,
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).strip() == b"1":
                    break
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError("Emulator did not boot within 180 seconds")
            time.sleep(2)

        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            run("adb", "shell", "settings", "put", "global", setting, "0")
        run("adb", "shell", "settings", "put", "secure", "spell_checker_enabled", "0")
        run("adb", "shell", "svc", "power", "stayon", "true")
        run("adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        run("adb", "shell", "wm", "dismiss-keyguard")

        # One invocation, both suites. Only the existing real-GPU leak tests stay excluded.
        run("./gradlew", "--build-cache", ":app:connectedAndroidDeviceTest",
            ":androidApp:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.example.myapplication.Filament3D",
            timeout=360)
        for module in ("app", "androidApp"):
            require_test_results(Path(module) / "build/outputs/androidTest-results/connected")
    finally:
        # Finish within the runner's cancellation grace period, even if adb never returns.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if emulator is not None:
            try:
                run("adb", "emu", "kill", timeout=3)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
            finally:
                stop_group(emulator)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        log = Path("build/ci-emulator.log")
        if log.exists():
            print("\n".join(log.read_text(errors="replace").splitlines()[-100:]), file=sys.stderr)
        sys.exit(1)
