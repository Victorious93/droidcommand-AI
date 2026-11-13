# Evidence: 2026-11-13 launch-crash repair

Raw, small outputs from the runs described in `docs/CRASH_REPAIR_REPORT.md`. Nothing here was edited except to copy it.
All emulator runs: x86_64 images under software emulation (no KVM). Not a physical device, not arm64.

| File | What it is |
|---|---|
| `api28-local-gate-validation-summary.md` | The `validation-summary.md` the gate wrote for the final source (6 tests + 22-run matrix). |
| `api28-local-fault-matrix-22-runs.tsv` | Per-run result and `resume_ms` for that matrix. |
| `api28-blocking-provider-BEFORE-fix.tsv` / `...AFTER-fix.tsv` | A provider that holds the query for 45 s: 56.8 s to resume before the fix, 10.4 s after (control 9.5 s / 10.7 s). |
| `api28-ui-soak-10min-instrument.log` | Opt-in `SoakTest`, 10 minutes, `OK (1 test)`. |
| `api35-cold-start-control-and-blocking-provider.tsv` | Android 15 cold starts (control and the blocking provider). |
| `api35-cold-start-run1-measurement-bug-wrong-event-tag.tsv` | A wrong result kept on purpose: my script read `am_on_resume_called`, which Android 15 renamed. Fixed in a later commit. |
| `api35-instrument-run*-killed-*.log` | Two instrumented runs on Android 15 that the SYSTEM killed before any test started (see the report, section 6). They are not app crashes. |

The real launch-crash runner log of main `25c202c` and the real passing run are test fixtures:
`scripts/ci/fixtures/instrument/real_launch_crash_25c202c.txt` and `real_gate_pass_0.1.7.txt`.
ANR stack traces and full logcat dumps were left out (large, device internals); they are summarized in the report.
