# Fleet downloads — how the Download list picks a build, and what each repo must publish

Written 2026-11-22. Code: `core-fleet-update` (`UpdatePlanner`, `FleetReleaseOptions`, `FleetDownloadQueue`, `FleetAutoUpdateMode`,
`ApkDownloader`) and `:app` (`FleetSources`, `FleetExtraApps`, `FleetInstallViewModel`, `FleetDownloadSection`). Status of the UI: compiled
and unit-tested in CI, launched on the CI emulator, **never exercised on a phone**.

## How a build is chosen

| Rule | Detail |
|---|---|
| Where from | GitHub Releases of the repo listed in `FleetSources`, read through the unauthenticated API (public repos only; VictorTrade is private, so it has no source). |
| Drafts | Never offered. A draft is invisible to the unauthenticated API as well as skipped by the planner: **publish the draft** to make a build downloadable. |
| Pre-releases | Skipped by "latest" and by **Download all** unless the owner turns on *Include pre-releases*. Each app's **Version** menu always lists them. |
| Which file | An `.apk` from the exact repo (the address is re-checked on every redirect), at most 300 MB, HTTPS only. A matching-ABI APK beats a universal one; a release-named APK beats a debug-named one; **an unsigned APK is never chosen** (Android refuses to install one; ShizukuV's `*-release-unsigned.apk` would otherwise have won over the debug APK beside it). |
| Integrity | SHA-256 is checked when GitHub publishes a digest. Android's own signature rule always applies: an update signed with a different key than the installed app is refused, and the owner confirms every install. |
| Versions | `v1.2.3`-style numbers are compared. Anything else (for example the rolling tag `debug-latest`) shows "Can't compare" and no update is claimed. |
| Older versions | Can be tried from the Version menu. Android usually refuses a downgrade; the row says so. Nothing is ever uninstalled by DroidCommand. |
| Resume | A stopped download is kept and resumes only for the **same asset**. A partial of another release is deleted, never appended (2026-11-21e). |

## What each repo has, and what the owner must do

Read on 2026-11-21 from tags and workflow files through anonymous git. GitHub's release flags and asset lists were checked through the API only for
VictorBots, ShizukuV and rustdeskV (each had **zero** releases). Everything else below is inferred from tags and workflows, so confirm on the
repo's Releases page.

| App | What exists | What it needs |
|---|---|---|
| VictorBots | No release. `release.yml` is manual, **production-signed**, and creates a **draft**. Its `AGENTS.md`: "No public release with the bundled debug key." | Set the four signing secrets (`VB_RELEASE_KEYSTORE_B64`, `VB_RELEASE_KEY_ALIAS`, `VB_RELEASE_STORE_PASS`, `VB_RELEASE_KEY_PASS`), run the workflow with a tag, then **publish the draft**. |
| ShizukuV | No release. CI only uploads workflow artifacts. Its release APK is **unsigned** unless the `release` environment has `KEYSTORE`, `KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS`, `KEYSTORE_ALIAS_PASSWORD`; its README: "There is no fallback to the debug key." The 69 tags up to `v13.6.0` look inherited from upstream Shizuku, not ShizukuV builds (likely). | Owner decision: provide the signing secrets, or knowingly publish the debug-signed APK (public debug key, proves nothing about who built it). Either way attach the APK to a GitHub Release. |
| rustdeskV | No release, no tags. The Android upload-to-GitHub step in `flutter-build.yml` is commented out; the other platforms' steps create `prerelease: true` releases. Android signing needs `ANDROID_SIGNING_KEY`. | Enable the Android upload, provide the key, push a `vX.Y.Z` tag. |
| RootForge OS | `v0.3.0` is the latest **stable** release. `v0.3.1` **exists as a published Pre-release** (body "Debug build from commit 070bfdd", 15 assets; their names could not be read), so the app hides it unless *Include pre-releases* is on, which is why a phone showed `v0.3.0`. The tag-triggered `release.yml` run for `v0.3.1` (#34) **failed**: its one error, on the `release` job, is `ReleaseAsset ... size must be less than 2147483648` (GitHub's 2 GiB per-asset limit, likely the ISO). No workflow in the repo writes that release body or sets the pre-release flag, so that release was made by hand or another tool. At tag `v0.3.1` the Android `versionName` is still `0.3.0`. | Nothing to publish: turn on *Include pre-releases* or pick `v0.3.1` from the Version menu. In the repo: bump `versionName` / `versionCode` before tagging (otherwise an installed `v0.3.1` APK still reads `0.3.0` and the app keeps offering it), and keep each release asset under 2 GiB. Read from the repo's public Actions and Releases pages on 2026-11-22; logs need a sign-in. |
| File-Manager, Victorious Mesh, VictorSMS | Workflows publish **debug-signed pre-releases**. | Nothing to build. Turn on *Include pre-releases* or pick the version from the menu. |
| VictorLaunch | Rolling pre-release tagged `debug-latest`. | Same as above. Installed copies show "Can't compare" for it. |
| HackerAI (`HackerAI-Commander`) | `android-release.yml` creates a **draft** with the signed APK. It is a same-signing bound companion: another key installs but its bind is refused. | **Publish the draft.** |
| EDL-Commander, VictorSpoof | Only `-debug` tags (EDL `v0.1.0-debug`, source 0.2.0-phase3; VictorSpoof `v0.1.1-debug`, source 0.2.0). No release workflow. | A release workflow or a manual release of the newer version. |
| VictorTaskbar, VictorMango, VictorGPS | Tag `v0.1.0-debug`, source 0.1.0, no release workflow. Whether a Release exists is unknown. | Confirm on the Releases page; add a release workflow if you want them automatic. |
| KYC-AuditLab, Victorious Browser, Victorious Flash, VictorCam, Pentest-Swarm | No tags and no release step found. | A release workflow or a manual release. |
| Victorious Keyboard | A `v0.1.0-debug-<sha>` tag per commit. Its workflow says debug builds "must never be published as a release". | Owner decision. |
| VictorTrade | Private repo. | No unauthenticated download is possible; no source is configured. |
| Termux / VictorSuite | `com.termux` until the rename lands. | No source is configured. |

## Why DroidCommand does not add release workflows to those repos

Publishing a signed or debug-signed APK is a signing decision, and three of these repos already state theirs (VictorBots: no public debug-key
release; ShizukuV: no debug fallback, no key stored; rustdeskV: Android upload disabled). A session that copies VictorSMS's "debug-signed
pre-release" workflow into them would override those rules. The owner makes that call, then the Download list picks the result up with no app
change.
