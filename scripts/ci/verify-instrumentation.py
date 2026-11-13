#!/usr/bin/env python3
"""Strict verdict on the raw output of `adb shell am instrument -w -r`.

Why this exists (docs/CRASH_REPAIR_REPORT.md): the previous gate only grepped for
"FAILURES!!!" and relied on adb's exit status. `adb shell` returns 0 even when the
instrumented process crashed, when the runner could not start, and when zero tests ran,
so all three passed the gate. This script instead requires AFFIRMATIVE evidence:

  * the run ended with `INSTRUMENTATION_CODE: -1` (Activity.RESULT_OK);
  * there is no `shortMsg=` / `longMsg=` / `Error=` in the result (crash / runner failure);
  * every test that started also finished, and each finished with status code 0;
  * at least one test ran, the runner's own `numtests` agrees with what finished, and the
    JUnit summary (`OK (N tests)`) agrees with the status stream;
  * every `--require CLASS#method` test is among the passed tests;
  * `--expect-count N` (optional) matches the passed count exactly.

Exit status: 0 = verified pass, 1 = FAIL (reasons on stderr), 2 = usage error.
Stdlib only, so it runs on a bare CI runner.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field

STATUS_OK = 0
STATUS_START = 1
STATUS_ERROR = -1
STATUS_FAILURE = -2
STATUS_IGNORED = -3
STATUS_ASSUMPTION_FAILURE = -4
STATUS_NAMES = {
    STATUS_OK: "ok",
    STATUS_START: "start",
    STATUS_ERROR: "error",
    STATUS_FAILURE: "failure",
    STATUS_IGNORED: "ignored",
    STATUS_ASSUMPTION_FAILURE: "assumption-failure",
}

_STATUS_RE = re.compile(r"^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$")
_STATUS_CODE_RE = re.compile(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$")
_RESULT_RE = re.compile(r"^INSTRUMENTATION_RESULT: ([^=]+)=(.*)$")
_CODE_RE = re.compile(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$")
_OK_SUMMARY_RE = re.compile(r"^OK \((\d+) tests?\)\s*$")
_FAIL_SUMMARY_RE = re.compile(r"^Tests run: (\d+),\s+Failures: (\d+)")


@dataclass
class Report:
    passed: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)
    ignored: list[str] = field(default_factory=list)
    started_unfinished: list[str] = field(default_factory=list)
    declared_numtests: int | None = None
    summary_ok_count: int | None = None
    final_code: int | None = None
    result_errors: list[str] = field(default_factory=list)
    saw_failures_banner: bool = False
    problems: list[str] = field(default_factory=list)


def parse(text: str) -> Report:
    report = Report()
    block: dict[str, str] = {}
    last_key: str | None = None
    started: dict[str, None] = {}
    in_result = False

    for raw in text.splitlines():
        line = raw.rstrip("\r")
        m = _STATUS_CODE_RE.match(line)
        if m:
            code = int(m.group(1))
            cls, test = block.get("class", ""), block.get("test", "")
            if "numtests" in block and block["numtests"].isdigit():
                report.declared_numtests = int(block["numtests"])
            if cls and test:
                ident = f"{cls}#{test}"
                if code == STATUS_START:
                    started[ident] = None
                else:
                    started.pop(ident, None)
                    if code == STATUS_OK:
                        report.passed.append(ident)
                    elif code == STATUS_IGNORED or code == STATUS_ASSUMPTION_FAILURE:
                        report.ignored.append(ident)
                    else:
                        report.failed.append(f"{ident} [{STATUS_NAMES.get(code, code)}]")
            block, last_key = {}, None
            continue
        m = _STATUS_RE.match(line)
        if m:
            last_key = m.group(1)
            block[last_key] = m.group(2)
            in_result = False
            continue
        m = _RESULT_RE.match(line)
        if m:
            in_result = True
            key, value = m.group(1), m.group(2)
            if key in ("shortMsg", "longMsg", "Error"):
                report.result_errors.append(f"{key}={value}")
            last_key = None
            continue
        m = _CODE_RE.match(line)
        if m:
            report.final_code = int(m.group(1))
            continue
        if "FAILURES!!!" in line:
            report.saw_failures_banner = True
        m = _OK_SUMMARY_RE.match(line)
        if m:
            report.summary_ok_count = int(m.group(1))
            continue
        if last_key is not None and not in_result:
            # Multi-line status values (stream=, stack=) continue until the next key.
            block[last_key] += "\n" + line
        if "INSTRUMENTATION_FAILED" in line or "Unable to find instrumentation" in line:
            report.result_errors.append(line.strip())

    report.started_unfinished = list(started)
    return report


def judge(report: Report, required: list[str], expect_count: int | None) -> list[str]:
    problems: list[str] = []
    if report.result_errors:
        problems.append("runner reported an error / crash: " + "; ".join(report.result_errors))
    if report.final_code is None:
        problems.append("no final INSTRUMENTATION_CODE line (runner died or output was truncated)")
    elif report.final_code != -1:
        problems.append(f"INSTRUMENTATION_CODE is {report.final_code}, expected -1 (RESULT_OK)")
    if report.saw_failures_banner:
        problems.append("JUnit printed FAILURES!!!")
    if report.failed:
        problems.append("failed tests: " + ", ".join(report.failed))
    if report.started_unfinished:
        problems.append("tests started but never finished (process crashed mid-test): " + ", ".join(report.started_unfinished))
    if not report.passed:
        problems.append("zero tests passed (a run with no executed tests is not a pass)")
    finished = len(report.passed) + len(report.failed) + len(report.ignored)
    if report.declared_numtests is not None and report.declared_numtests != finished:
        problems.append(f"runner declared numtests={report.declared_numtests} but {finished} finished")
    if report.summary_ok_count is None:
        problems.append("no `OK (N tests)` summary from JUnit (run did not complete affirmatively)")
    elif report.summary_ok_count != len(report.passed):
        problems.append(f"JUnit summary says {report.summary_ok_count} tests, status stream shows {len(report.passed)} passed")
    for name in required:
        if name not in report.passed:
            problems.append(f"required test did not pass (missing or not run): {name}")
    if expect_count is not None and len(report.passed) != expect_count:
        problems.append(f"expected exactly {expect_count} passed tests, saw {len(report.passed)}")
    if report.ignored:
        # Ignored / assumption-failed tests are not evidence of anything.
        problems.append("ignored or assumption-failed tests present: " + ", ".join(report.ignored))
    return problems


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", help="file holding the raw `am instrument -w -r` output")
    ap.add_argument("--require", action="append", default=[], metavar="CLASS#method",
                    help="test that must have passed (repeatable)")
    ap.add_argument("--expect-count", type=int, default=None, help="exact number of passed tests")
    ap.add_argument("--summary", default=None, help="write a one-paragraph markdown summary here")
    args = ap.parse_args(argv)

    try:
        with open(args.log, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        print(f"cannot read {args.log}: {exc}", file=sys.stderr)
        return 2

    report = parse(text)
    problems = judge(report, args.require, args.expect_count)
    verdict = "FAIL" if problems else "PASS"
    summary = (
        f"{verdict}: {len(report.passed)} passed, {len(report.failed)} failed, "
        f"{len(report.ignored)} ignored, final code {report.final_code}"
    )
    print(summary)
    for name in report.passed:
        print(f"  passed  {name}")
    for problem in problems:
        print(f"  PROBLEM {problem}", file=sys.stderr)
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as out:
            out.write(f"- {summary}\n")
            for name in report.passed:
                out.write(f"  - `{name}`\n")
            for problem in problems:
                out.write(f"  - PROBLEM: {problem}\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
