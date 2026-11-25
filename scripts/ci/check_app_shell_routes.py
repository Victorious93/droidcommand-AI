#!/usr/bin/env python3
"""Fail if the Android app (:app) wires a shell path that bypasses VictorSuite.

On a phone every product shell action goes through VictorSuite (com.termux): RUN_COMMAND, its shell broker, or its
on-device approval. DroidCommand's own process executors stay valid for the JVM `cli` only. This guard scans
app/src/main (Kotlin and Java, comments skipped) and exits 1 on any forbidden use. The few process spawns that remain
are fixed-argv and listed in ALLOWED with the reason; adding one needs an explicit edit here that a reviewer will see.

Usage: python3 -I scripts/ci/check_app_shell_routes.py [repo_root]
"""
import pathlib
import re
import sys

FORBIDDEN = [
    (r"\bProcessBuilderShellExecutor\b", "core-shell ProcessBuilder executor runs in DroidCommand's own sandbox"),
    (r"\bLocalProcessExecutionTarget\b", "local process target bypasses VictorSuite"),
    (r"\bShellBackedMetasploitExecutor\b", "ShellBacked Metasploit uses the core-shell executor; use TermuxMetasploitExecutor"),
    (r"\bAdbRootExecutor\b", "root over adb is a host tool, not a phone product shell"),
    (r"\bAdbTermuxExecutor\b", "adb Termux scrape is a host tool; on a phone use ClientBackedTermuxExecutor"),
    (r"\bMagiskProvider\b", "direct su provider bypasses VictorSuite's privileged switch"),
    (r"\bPolicyEnforcingRootExecutor\b", "direct root executor bypasses VictorSuite's privileged switch"),
    (r"\bShizukuToolKind\.EXEC\b", "arbitrary privileged command over DroidCommand's Shizuku service"),
    (r"\bShizukuTool\.all\s*\(", "registers run_shizuku_command; use ShizukuTool.fixedPurpose"),
    (r"\bRootTool\s*\((?!\s*NullRootExecutor\s*\(\s*\)\s*\))", "RootTool must stay on NullRootExecutor in the app"),
    (r"\bShellTool\s*\(", "ShellTool must only wrap the VictorSuite-routed AllowlistShellExecutor (AutomationTools.kt)"),
    (r"\bProcessBuilder\s*\(", "spawns a process in DroidCommand's own sandbox"),
    (r"Runtime\.getRuntime\(\)\s*\.exec\s*\(", "spawns a process in DroidCommand's own sandbox"),
    (r"\bAllowlistShellExecutor\s*\{", "AllowlistShellExecutor must be given the VictorSuite TermuxExecutor"),
]

# (path relative to app/src/main, pattern) -> reason. Keep this list short and specific.
ALLOWED = {
    ("kotlin/ai/droidcommand/app/automation/AutomationTools.kt", r"\bShellTool\s*\("):
        "AutomationShellTool wraps an injected ShellExecutor; AppModule passes AllowlistShellExecutor(termux)",
    ("kotlin/ai/droidcommand/app/shizuku/ToolboxRuntime.kt", r"\bProcessBuilder\s*\("):
        "System Toolbox root backend: fixed vetted commands from core-shizuku's toolbox, owner confirms each one",
    ("kotlin/ai/droidcommand/app/shizuku/embedded/EmbeddedShizukuStarter.kt", r"\bProcessBuilder\s*\("):
        "starts the built-in Shizuku server with fixed su argv on the owner's tap; no user-supplied command",
}

COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def scan(root: pathlib.Path):
    main = root / "app" / "src" / "main"
    problems = []
    for path in sorted(list(main.rglob("*.kt")) + list(main.rglob("*.java"))):
        rel = path.relative_to(main).as_posix()
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if COMMENT.match(line):
                continue
            code = line.split("//", 1)[0]
            for pattern, why in FORBIDDEN:
                if re.search(pattern, code) and (rel, pattern) not in ALLOWED:
                    problems.append(f"app/src/main/{rel}:{lineno}: {why}")
    return problems


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else ".").resolve()
    if not (root / "app" / "src" / "main").is_dir():
        print(f"no app/src/main under {root}", file=sys.stderr)
        return 2
    problems = scan(root)
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} shell route(s) bypass VictorSuite. See VictorSuite docs/SHELL_ROUTING.md.", file=sys.stderr)
        return 1
    print("app shell routes OK: every product shell path goes through VictorSuite")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
