"""Exercise CI process cleanup without an SDK, emulator, or Gradle installation."""

import importlib.util
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).with_name("run_android_device_tests.py").resolve()
spec = importlib.util.spec_from_file_location("runner", SCRIPT)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

FIXTURE = r'''
import os
from pathlib import Path
import signal
import sys
import time

command = Path(sys.argv[0]).name
if command == "emulator":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    Path("emulator.pid").write_text(str(os.getpid()))
    child = os.fork()
    if child == 0:
        Path("child.pid").write_text(str(os.getpid()))
    while True:
        time.sleep(1)
elif command == "adb":
    if "getprop" in sys.argv:
        while not Path("child.pid").exists():
            time.sleep(.01)
        if os.getenv("BOOT_FAIL"):
            os.kill(int(Path("emulator.pid").read_text()), signal.SIGKILL)
            sys.exit(1)
        print("1")
    elif "kill" in sys.argv and os.getenv("ADB_KILL_HANG"):
        time.sleep(60)
elif command == "gradlew":
    Path("gradle.pid").write_text(str(os.getpid()))
    Path("gradle-args").write_text("\n".join(sys.argv[1:]))
    Path("tests.started").touch()
    if os.getenv("TESTS_WAIT"):
        time.sleep(60)
    if os.getenv("TESTS_FAIL"):
        sys.exit(23)
    for module in ("app", "androidApp"):
        directory = Path(module) / "build/outputs/androidTest-results/connected/debug"
        directory.mkdir(parents=True)
        tests = "0" if os.getenv("ZERO_TESTS") == module else "2"
        (directory / "TEST-example.xml").write_text('<testsuite tests="' + tests + '"/>')
'''


class AndroidDeviceRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cwd = Path(self.temp.name)
        self.process = None
        for name in ("bin/adb", "bin/avdmanager", "sdk/emulator/emulator", "gradlew"):
            file = self.cwd / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(f"#!{sys.executable}\n" + FIXTURE)
            file.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{self.cwd / 'bin'}:{os.environ['PATH']}",
                        ANDROID_HOME=str(self.cwd / "sdk"), ANDROID_AVD_HOME=str(self.cwd / "avd"))

    def tearDown(self):
        if self.process and self.process.poll() is None:
            self.process.kill()
            self.process.communicate()
        # Ensure a failing assertion cannot leave a fixture process behind.
        for file in self.cwd.glob("*.pid"):
            try:
                os.kill(int(file.read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass

    def start(self, **env):
        for file in self.cwd.glob("*.pid"):
            file.unlink()
        (self.cwd / "tests.started").unlink(missing_ok=True)
        self.process = subprocess.Popen([sys.executable, str(SCRIPT)], cwd=self.cwd,
                                        env=dict(self.env, **env), stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True)

    def finish(self, expected):
        output, _ = self.process.communicate(timeout=12)
        self.assertEqual(expected, self.process.returncode, output)
        for name in ("emulator.pid", "child.pid", "gradle.pid"):
            file = self.cwd / name
            if file.exists():
                status = subprocess.run(["ps", "-p", file.read_text(), "-o", "stat="],
                                        capture_output=True, text=True).stdout.strip()
                self.assertTrue(not status or status.startswith("Z"), f"{name} still running: {status}")
        return output

    def test_success_stops_emulator_and_child(self):
        self.start()
        self.finish(0)
        args = (self.cwd / "gradle-args").read_text()
        self.assertIn(":app:connectedAndroidDeviceTest", args)
        self.assertIn(":androidApp:connectedDebugAndroidTest", args)

    def test_failed_tests_still_stop_emulator(self):
        self.start(TESTS_FAIL="1")
        self.assertIn("23", self.finish(1))

    def test_zero_tests_in_either_suite_fail(self):
        # Use a separate temporary workspace per suite to avoid stale result files.
        for module in ("app", "androidApp"):
            with self.subTest(module=module):
                for directory in ("app", "androidApp"):
                    shutil.rmtree(self.cwd / directory, ignore_errors=True)
                self.start(ZERO_TESTS=module)
                self.assertIn("No device tests ran", self.finish(1))

    def test_crashed_boot_stops_remaining_child(self):
        self.start(BOOT_FAIL="1")
        self.assertIn("before completing boot", self.finish(1))

    def test_hung_adb_shutdown_is_bounded(self):
        started = time.monotonic()
        self.start(ADB_KILL_HANG="1")
        self.finish(0)
        self.assertLess(time.monotonic() - started, 8)

    def test_cancellation_during_tests_stops_emulator(self):
        self.start(TESTS_WAIT="1", ADB_KILL_HANG="1")
        deadline = time.monotonic() + 5
        while not (self.cwd / "tests.started").exists():
            self.assertLess(time.monotonic(), deadline, "Fixture never reached tests")
            time.sleep(.02)
        self.process.send_signal(signal.SIGTERM)
        self.finish(143)

    def test_interrupt_during_tests_stops_emulator(self):
        self.start(TESTS_WAIT="1")
        deadline = time.monotonic() + 5
        while not (self.cwd / "tests.started").exists():
            self.assertLess(time.monotonic(), deadline, "Fixture never reached tests")
            time.sleep(.02)
        self.process.send_signal(signal.SIGINT)
        self.finish(130)

    def test_hung_command_times_out_and_kills_children(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            runner.run(sys.executable, "-c", "import time; time.sleep(60)", timeout=.05)


if __name__ == "__main__":
    unittest.main()
