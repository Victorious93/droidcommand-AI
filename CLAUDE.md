# DroidCommand AI — orientation for Claude sessions

Read this first. It exists so a new session doesn't have to re-derive
context that already lives elsewhere in this repo — don't re-audit the
project or re-clone the reference repos unless the task genuinely requires
re-verifying something.

## Where the real state lives

- **`docs/REQUIREMENTS_PROMPT.md`** — the project owner's master
  audit/build prompt, stored verbatim. This is the requirements baseline
  (`ROADMAP-###` IDs are derived from its 34 numbered sections). Treat it as
  a durable substitute for chat memory, not as something to re-paste.
- **`docs/AUDIT_2026-09-05.md`** — the current source of truth for status:
  a full `ROADMAP-###`/`DP-###`/`OD-###` requirements matrix, each item's
  real status (VERIFIED IMPLEMENTED / PARTIAL / STUB / MISSING / BLOCKED,
  never inflated), a priority list (P0–P4), and a dated addendum trail of
  what's shipped since. **Read its addenda section first** — it tracks what
  changed after the original audit ran, newest at the bottom.
- **`docs/ARCHITECTURE.md`** — per-component implementation status, kept in
  sync with the audit.
- **`docs/CORE_BUILD.md`** — the build/workspace pipeline specifically.

## What's already been verified (don't re-derive)

- This is a 14-module pure-Kotlin/JVM Gradle project. No Android SDK, no
  device, no root, no LLM credentials, no MCP client exist in most build
  environments this project runs in — everything downstream of those
  (device control, root execution, live LLM calls, real builds/APKs) is
  correctly marked PLANNED/BLOCKED in the audit, not faked.
- The codebase is unusually honest: every intentional non-real
  implementation (`MockBuildExecutor`, `Null*Executor`, `Null*Controller`)
  is self-documented as such in its own code. If you find something that
  looks like a stub, check whether it's already an acknowledged one before
  treating it as a new finding.
- `docs/AUDIT_2026-09-05.md` already did the full three-way comparison
  against the two reference projects, DroidPilot
  (`https://github.com/Victorious93/droidpilot`) and OpenDroid
  (`https://github.com/Victorious93/opendroid`). Their relevant findings
  are extracted into `DP-###`/`OD-###` rows — read those rather than
  re-cloning and re-inspecting either repo, unless a specific task needs a
  fresh lookup neither doc covers.

## How to continue the work

1. Run `./gradlew test --continue` to see real, current status — don't
   trust a stale claim (including this file's).
2. Read the audit doc's priority list and addenda to find the next
   unclaimed item.
3. Implement it in the smallest safe unit, following the pattern already
   established in the module you're touching (e.g. `core-security`'s
   fail-closed, additive-by-default style).
4. Add tests, run the full suite, keep the change minimal.
5. Update `docs/ARCHITECTURE.md` and append a dated addendum to
   `docs/AUDIT_2026-09-05.md` (don't rewrite its existing entries — append,
   per the project's own "no silent history rewriting" rule).
6. Open a PR, watch it through CI, keep going.

If the branch's own PR has already merged before you start new work,
restart the branch from `origin/main` (`git fetch origin main && git
checkout -B <branch> origin/main`) rather than stacking on stale history.

## Still open

- A separate "capability/verification prompt" (`CAP-###` in the audit's
  vocabulary) was described by the project owner as pasted earlier in a
  different chat thread and was never recovered — Claude Code sessions
  have no cross-session memory. If the owner supplies it, audit it the same
  way and fold it into `docs/AUDIT_2026-09-05.md`.
