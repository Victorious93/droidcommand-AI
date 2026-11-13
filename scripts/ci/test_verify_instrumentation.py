#!/usr/bin/env python3
"""Harness regression tests for verify-instrumentation.py (the emulator gate).

Run: python3 scripts/ci/test_verify_instrumentation.py
Each fixture is the shape `adb shell am instrument -w -r` prints. adb exits 0 for ALL of them,
which is why the old gate (`grep FAILURES!!!` + adb's exit status) passed crashed and empty runs.
`OldGateRegression` pins that: it replays the old logic and asserts it accepts the bad runs,
so a future "simplification" back to grep cannot go unnoticed.
"""
import importlib.util
import pathlib
import subprocess
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "instrument"
SCRIPT = HERE / "verify-instrumentation.py"

spec = importlib.util.spec_from_file_location("verify_instrumentation", SCRIPT)
vi = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = vi  # dataclasses needs the module registered before exec
spec.loader.exec_module(vi)

LAUNCH = "ai.droidcommand.app.MainActivityLaunchTest#launchesToResumedWithoutCrashing"
RESILIENCE = "ai.droidcommand.app.StartupResilienceTest#survivesCorruptState"


def problems(name, require=(), expect_count=None):
    text = (FIX / name).read_text()
    return vi.judge(vi.parse(text), list(require), expect_count)


def old_gate_accepts(name):
    """The pre-repair gate: adb exit status 0 (always, for these) and no 'FAILURES!!!' line."""
    return "FAILURES!!!" not in (FIX / name).read_text()


class Verdicts(unittest.TestCase):
    def test_success_single(self):
        self.assertEqual(problems("success_one.txt", [LAUNCH], 1), [])

    def test_success_two_required(self):
        self.assertEqual(problems("success_two.txt", [LAUNCH, RESILIENCE], 2), [])

    def test_crash_mid_test_fails(self):
        p = problems("crashed_mid_test.txt", [LAUNCH])
        self.assertTrue(any("crash" in x for x in p), p)
        self.assertTrue(any("never finished" in x for x in p), p)

    def test_crash_before_any_test_fails(self):
        p = problems("crashed_before_tests.txt")
        self.assertTrue(any("crash" in x or "error" in x for x in p), p)
        self.assertTrue(any("zero tests" in x for x in p), p)

    def test_zero_tests_fails(self):
        p = problems("zero_tests.txt")
        self.assertTrue(any("zero tests" in x for x in p), p)

    def test_assertion_failure_fails(self):
        p = problems("assertion_failure.txt", [LAUNCH])
        self.assertTrue(any("failed tests" in x for x in p), p)
        self.assertTrue(any("FAILURES" in x for x in p), p)

    def test_runner_missing_fails(self):
        self.assertTrue(problems("runner_missing.txt"))

    def test_truncated_output_fails(self):
        p = problems("truncated.txt", [LAUNCH])
        self.assertTrue(any("no final INSTRUMENTATION_CODE" in x for x in p), p)

    def test_required_test_missing_fails(self):
        p = problems("success_one.txt", [LAUNCH, RESILIENCE])
        self.assertTrue(any(RESILIENCE in x for x in p), p)

    def test_count_mismatch_fails(self):
        self.assertTrue(problems("success_one.txt", [LAUNCH], 2))


class OldGateRegression(unittest.TestCase):
    """These all printed no FAILURES!!! line, so the old gate called them green."""

    def test_old_gate_accepted_crash_and_empty_runs(self):
        for name in ("crashed_mid_test.txt", "crashed_before_tests.txt", "zero_tests.txt", "truncated.txt", "runner_missing.txt"):
            self.assertTrue(old_gate_accepts(name), f"{name}: old gate would have rejected it, fixture no longer reproduces the hole")
            self.assertTrue(problems(name), f"{name}: new gate must reject it")

    def test_old_gate_did_reject_plain_failure(self):
        self.assertFalse(old_gate_accepts("assertion_failure.txt"))


class CommandLine(unittest.TestCase):
    def run_cli(self, name, *extra):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(FIX / name), *extra], capture_output=True, text=True)

    def test_exit_codes(self):
        self.assertEqual(self.run_cli("success_one.txt", "--require", LAUNCH, "--expect-count", "1").returncode, 0)
        for bad in ("crashed_mid_test.txt", "crashed_before_tests.txt", "zero_tests.txt", "assertion_failure.txt", "truncated.txt"):
            self.assertEqual(self.run_cli(bad).returncode, 1, bad)

    def test_unreadable_log_is_usage_error(self):
        r = subprocess.run([sys.executable, "-I", str(SCRIPT), str(FIX / "nope.txt")], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
