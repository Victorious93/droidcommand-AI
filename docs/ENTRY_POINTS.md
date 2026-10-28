# Inbound droidcommand-AI companion entry points

This is the inbound direction: a fleet app starts
`ai.droidcommand.action.COMPANION_ASSIST` (or, for VictorFiles,
`ai.droidcommand.action.CODE_ASSIST` in PR #255) and this app owns the
model, keys, and network.

Contract (COMPANION_ASSIST v1):

- Exported Activity, `DEFAULT` category, `startActivityForResult`.
- Request extra `ai.droidcommand.extra.REQUEST_JSON`: `v`, `task`
  (`assist` only), `prompt` (max 60 000 chars), optional `context`,
  `instruction`, `capability`, `destructive`.
- Result extra `ai.droidcommand.extra.RESULT_JSON` with `RESULT_OK`:
  `{v:1, ok:true, text}` or `{v:1, ok:false, error}`.
- `RESULT_CANCELED` on deny, missing/mismatched caller, excluded app,
  pending-refused cert, or bad input.
- Consent is a real Allow/Deny tap. The model is never called before
  Allow. Never auto-approves. Never runs tools or `remote.command`.
- A `destructive: true` flag is **confirmation_required** (the Allow
  tap). It does not execute a destructive action.
- External control stays OFF by default. This path does not turn on
  outbound fleet command.

Caller identity is `getCallingPackage()` / `getCallingActivity()`.
Package **and** signing cert are checked with
`NodeSignatureGuard` / `NodeSignatureVerifier` against
`KnownNodeSigningCatalog` / `KnownFleet`. There is **no second cert
list**. Missing identity, unknown package, excluded app, undocumented
or unenforced cert, and cert mismatch all fail closed.

Code: `core-security.InboundEntryPointCatalog` /
`CompanionAssist`. Android shell:
`CompanionAssistActivity`.

Depends on PR #255 (File-Manager CODE_ASSIST) only as the pattern.
This catalogue generalizes that PR's caller allow-list
(`callerIdentities("code-assist")`) so #255 can rebase onto it.

[Certain] JVM-tested. [Certain] no Android SDK / device / emulator in
the session that wrote this. [Likely] the Activity would behave like
CODE_ASSIST on a device once Allow is tapped.

## Included (13)

rootforge-os, VictorGPS, and Victorious Keyboard are **later additions
— Don to confirm**.

| App | Package id | Cert status | Capability |
|---|---|---|---|
| VictorSuite | `com.termux` (live default; pending `com.victorious.suite` is not an inbound caller) | **enforced** (debug + VictorSuite-own release from `KnownNodeSigningCatalog`) | `companion.assist.victorsuite` |
| VictorSMS | `com.victorious.sms` | **pending-refused** — debug SHA-256 is documented but unenforced (`VICTOR_SMS_DEBUG_SHA256_UNENFORCED`); inbound must not use it | `companion.assist.victorsms` |
| VictorCam | `com.victorious.cam` | **pending-refused** — no catalogued fingerprint | `companion.assist.victorcam` |
| VictorTrade | `com.victorious.trade` | **pending-refused** — no public repo and no KnownFleet row. Package id is Don-specified in the inbound-entry-points request **[Guessing]** | `companion.assist.victortrade` |
| VictorTaskbar | `com.victorious.taskbar` | **pending-refused** — no catalogued fingerprint (README-only scaffold) | `companion.assist.victortaskbar` |
| VictorLaunch | `com.victorious.launch` | **enforced** (`VICTOR_LAUNCH_DEBUG_SHA256`) | `companion.assist.victorlaunch` |
| Victorious Browser | `com.victorious.browser` | **enforced** (`VICTORIOUS_BROWSER_DEBUG_SHA256`) | `companion.assist.browser` |
| Victorious Mesh | `com.victorious.mesh` | **enforced** (debug + own release) | `companion.assist.mesh` |
| VictorMango | `com.victorious.mango` | **pending-refused** — fleet-manifest `pinnedCertSha256` is empty | `companion.assist.victormango` |
| VictorSpoof | `com.victorious.spoof` | **pending-refused** — KnownFleet certs TBD | `companion.assist.victorspoof` |
| rootforge-os | `ai.rootforge.os.debug` **enforced**; `ai.rootforge.os` **pending-refused** (no release pin) | mixed — see packages | `companion.assist.rootforge` |
| VictorGPS | `com.victorious.gps` | **pending-refused** — debug keystore exists in their repo; SHA-256 is **not** catalogued here and was not invented | `companion.assist.victorgps` |
| Victorious Keyboard | `com.victorious.keyboard` / `.debug` | **enforced** (`KEYBOARD_DEBUG_SHA256`). `.bench` is not a caller | `companion.assist.keyboard` |

Keyboard never forwards typed text or keystrokes. VictorSpoof assist
never places a call. No inbound path runs wipe / power-off / lock /
flash / bootloader.

## Excluded (must not be controllable by droidcommand-AI)

| App | Package id | Reason |
|---|---|---|
| VictoryPed | `com.victorious.victoryped` | Social / pedigree. Private. fleet-manifest ROADMAP class SOCIAL. Not a remote-control fleet member. |
| VictorLink64 | `org.victoriousframework.victorlink64` | Game emulator. fleet-manifest `NOT_IN_FLEET_REMOTE`. Explicitly out of the fleet index. |
| MatchVictorious | `com.victorious.match` | Dating import hub. fleet-manifest ROADMAP class SOCIAL. |
| VictorOps Zombies | `com.victorious.zombie` | Game. fleet-manifest `NOT_IN_FLEET_REMOTE`. |

`InboundEntryPointTest` refuses all four even when a cert is supplied.

## Pattern row (not one of the 13)

| App | Package id | Cert status | Capability |
|---|---|---|---|
| VictorFiles CODE_ASSIST (PR #255) | `ai.victorious.filemanager` / `.debug` | inbound **enforced** via `FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS`; outbound Messenger stays fail-open | `code-assist` |

## TODOs (do not invent fingerprints)

- VictorSMS: enforce `VICTOR_SMS_DEBUG_SHA256_UNENFORCED` only after the scaffold merges; then pin RELEASE.
- VictorCam: pin debug + release in `KnownNodeSigningCatalog` when Don publishes them.
- VictorTrade: confirm `applicationId` from a public repo; then pin debug + release.
- VictorTaskbar: pin debug + release when an APK ships.
- VictorMango: pin debug + release (`apps.yml` pin list is empty).
- VictorSpoof: pin debug + release (KnownFleet list is empty).
- rootforge-os: pin `ai.rootforge.os` RELEASE. Debug is already catalogued.
- VictorGPS: catalog the debug SHA-256 from their committed keystore (do not invent); then pin RELEASE.
- Keyboard: pin RELEASE. `.bench` stays off the inbound list.
- VictorSuite: pin `com.victorious.suite` after PR #51 merges. Live inbound stays `com.termux`.
- VictorLaunch / Browser: pin own-key RELEASE when published.
- File-Manager CODE_ASSIST (#255): pin RELEASE when one exists.

## Honesty

- [Certain] package ids for every included app except VictorTrade were
  read from `KnownFleet` and/or the app's own `applicationId` /
  fleet-manifest ROADMAP.
- [Guessing] VictorTrade `com.victorious.trade` — named in the request;
  no public `Victorious93` repo and no KnownFleet row on 2026-10-28.
- [Certain] no signing cert was invented. Unenforced / undocumented
  certs fail closed.
- [Certain] no device or emulator ran this Activity.
