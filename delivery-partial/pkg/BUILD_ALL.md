# Coordinated build / validation run

```sh
python3 tools/build-all/run.py --workspace /path/holding/all/checkouts --out /path/to/out     # full run, concurrent within --cpus
python3 tools/build-all/run.py --workspace … --out <same out> --retry-failed                    # re-run only what did not PASS (+ needed deps)
python3 tools/build-all/run.py … --only dc-apk --with-deps ; python3 tools/build-all/run.py --list
```
Outputs: `results.json` (per job: command, status, exit code, seconds, log path, artifacts with SHA-256; per repo: commit, branch, uncommitted-file count and hash,
submodules, host CPU/RAM), `MATRIX.md` (PASSED / FAILED / BLOCKED / NOT RUN), `logs/<job>.log`. Exit code ≠ 0 unless every *required* job PASSED.
Status rules: **PASSED** = command exited 0; **FAILED** = ran, non-zero; **BLOCKED** = a needed tool/resource is absent (probed first; never faked);
**NOT RUN** = dependency did not pass or not selected. Jobs run in the owning repo's checkout with their own env; nothing is signed, published or merged.
Job list and the repository-documented commands: `tools/build-all/jobs.json`. Android jobs need `ANDROID_HOME` (+ NDK, Go for Mesh, Flutter for rustdeskV).
Limits: the runner builds from the working trees as they are (it records the dirty state, it does not make a clean snapshot copy); it does not resolve pairing pins
from built APK identities or rebuild consumers; it does not boot-test an ISO.

## Run recorded 2026-11-14 on the 4-vCPU / 15 GiB build host (no Android SDK, Flutter, GTK/WebKit, container daemon or live-build)
See `docs/evidence-build-all/MATRIX.md` (copied from that run). PASSED: fleet coordinator tests (43), Debian logic tests, Arch render + headless-Chromium UI,
Flash `cargo test` + helper/bridge release build, DroidCommand JVM tests (fleet worker incl. real coordinator interop, screen adapter, remote-pc), feed schema validation.
BLOCKED: every Android APK (DroidCommand, VictorSuite, File-Manager, Mesh, rustdeskV), rustdeskV Linux client, Flash Tauri bundle, both ISO builds. **Therefore the
five-APK matched set was NOT built and no ISO was built or booted.** Pairing pins and fleet-manifest were not updated: there are no produced artifacts to derive them from.
