# DroidCommand AI — Companion APK Integration Protocol

This document describes how DroidCommand AI integrates with external companion
APKs (currently HackerAI and Pentest-Swarm) via Android Bound Services. Read it
before modifying AIDL contracts, adding new companion capabilities, or working
on `core-companion` / `core-hackerai` / `core-pentest-swarm`.

## Overview

DCA's built-in modules (`core-agent`, `core-security`, etc.) run on the JVM and
do not require an Android device. The companion APKs extend DCA with capabilities
that either need a bundled binary (Pentest-Swarm) or a full LLM agent loop
(HackerAI), which would bloat DCA's own APK if bundled directly.

```
DroidCommand AI (ai.droidcommand.app)
        │
        ├── bindService() + ai.droidcommand.permission.BIND_HACKERAI
        │       └── HackerAI companion APK (ai.hackerai.companion)
        │               └── IHackerAIService.Stub
        │
        └── bindService() + ai.droidcommand.permission.BIND_PENTESTSWARM
                └── Pentest-Swarm companion APK (ai.pentestswarm.companion)
                        └── IPentestSwarmService.Stub
                                └── SwarmProcess → pentestswarm binary (Go)
```

Both companions are **optional and independently installable**. If a companion
is absent, its `CapabilityHealthChecker` returns `REQUIRES_EXTERNAL_SERVICE`
and DCA's tools (`HackerAiTool`, `SwarmTool.*`) return `ToolResult.Failure`.

## Security Model

### OS-level enforcement (signature permission)

DCA declares two custom permissions in its `AndroidManifest.xml`:

```xml
<permission android:name="ai.droidcommand.permission.BIND_HACKERAI"
    android:protectionLevel="signature" />
<permission android:name="ai.droidcommand.permission.BIND_PENTESTSWARM"
    android:protectionLevel="signature" />
```

`protectionLevel="signature"` means Android will only grant these permissions
to APKs signed with the same certificate as DCA. No other app — not even a
rooted one — can bind to the companion services.

Each companion service declares the matching permission:

```xml
<!-- HackerAI companion -->
<service android:name=".service.HackerAIBoundService"
    android:exported="true"
    android:permission="ai.droidcommand.permission.BIND_HACKERAI" />

<!-- Pentest-Swarm companion -->
<service android:name=".service.PentestSwarmBoundService"
    android:exported="true"
    android:permission="ai.droidcommand.permission.BIND_PENTESTSWARM" />
```

### App-level enforcement (DependencyGuard)

Every companion APK has a `DependencyGuard` that checks `ai.droidcommand.app`
is installed at every AIDL call. If DCA is absent:

- All methods return `{"ok":false,"error":"DroidCommand AI is not installed"}`
- The UI shows a "DCA required" message and disables all functionality

This is a defence-in-depth measure — the signature permission already prevents
an attacker from binding, but `DependencyGuard` also prevents a compromised
companion from operating if DCA is later removed.

### Initiator mapping

All AIDL calls that arrive at DCA's tool executor are dispatched with
`Initiator.REMOTE`, triggering the full `SecureToolExecutor` policy/approval/
grant/audit path. Companion tools are never silently bypassed.

## AIDL Interface Specifications

### `IHackerAIService.aidl`

```aidl
package ai.droidcommand.companion;

interface IHackerAIService {
    // Start an agent task. Input: CreateAgentInput JSON.
    // Returns: {"ok":true,"taskId":"..."} or {"ok":false,"error":"..."}
    String runAgentTask(String inputJson);

    // Returns the full Strix skill catalog as a JSON array.
    // Each entry: {id, name, description, category, tags}
    String getSkillCatalog();

    // Validate a security finding candidate.
    // Input: SecurityFindingCandidate JSON.
    // Returns: SecurityValidationResult JSON.
    String validateFinding(String candidateJson);

    // Cancel a running agent task. Fire-and-forget.
    oneway void cancelTask(String taskId);

    // Returns "ok" | "busy" | "no_llm_provider" | "dca_not_installed"
    String healthCheck();
}
```

### `IPentestSwarmService.aidl`

```aidl
package ai.droidcommand.companion;

interface IPentestSwarmService {
    // Create and start a campaign. Input: CreateCampaignRequest JSON.
    // Returns: {"ok":true,"campaignId":"...","status":"..."} or error JSON.
    String startCampaign(String createCampaignRequestJson);

    // Stop a running campaign.
    // Returns: {"ok":true,"campaignId":"...","status":"..."} or error JSON.
    String stopCampaign(String campaignId);

    // Get current status of a campaign.
    // Returns: {"ok":true,"campaign":{...CampaignResponse}} or error JSON.
    String getCampaignStatus(String campaignId);

    // Get all findings for a campaign.
    // Returns: {"ok":true,"findings":[...FindingResponse]} or error JSON.
    String getFindings(String campaignId);

    // Run an exploit chain against a target.
    // scopeJson: JSON array of allowed host strings.
    // Returns: {"ok":true,"executionId":"...","status":"..."} or error JSON.
    String runChain(String chainId, String target, String scopeJson);

    // Run a playbook against a target.
    // variablesJson: JSON object {"varName":"value"}.
    // Returns: {"ok":true,"executionId":"...","status":"..."} or error JSON.
    String runPlaybook(String playbookId, String target, String scopeJson,
                       String variablesJson);

    // List available exploit chains.
    // Returns: {"ok":true,"chains":[...ChainSummary]} or error JSON.
    // Falls back to embedded ChainCatalog if swarm process is not running.
    String listChains();

    // List available playbooks.
    // Returns: {"ok":true,"playbooks":[...PlaybookSummary]} or error JSON.
    // Falls back to embedded PlaybookCatalog if swarm process is not running.
    String listPlaybooks();

    // Returns true if the Go pentestswarm process is running.
    boolean isSwarmReady();

    // Restart the swarm process. Fire-and-forget.
    oneway void restartSwarm();
}
```

### JSON transport rationale

All methods pass and return JSON strings rather than typed Parcelables. This
means schema changes (adding a field to `CampaignResponse`, renaming a key in
`FindingResponse`) do not require changes to the AIDL file, which would trigger
a Binder interface mismatch on the device. Both sides share the same
`@Serializable` data class definitions from `core-hackerai`/`core-pentest-swarm`,
so a schema change is a coordinated library update, not an AIDL renegotiation.

### Error JSON contract

All string-returning methods return valid JSON in one of two shapes:

```json
{"ok": true, ...method-specific fields...}
{"ok": false, "error": "human-readable message"}
```

Callers must check `ok` before accessing other fields. The `error` field is
always present when `ok` is false. Special characters in `error` values are
escaped (backslash and double-quote).

## Build and Publish Workflow

### Step 1: Build the JVM library modules

```bash
cd droidcommand-AI/
./gradlew :core-hackerai:build :core-pentest-swarm:build
```

Both are in `settings.gradle.kts` and build as part of the normal JVM build.

### Step 2: Publish to Maven Local

The companion APKs resolve these libraries from `mavenLocal()`:

```bash
cd droidcommand-AI/
./gradlew :core-hackerai:publishToMavenLocal :core-pentest-swarm:publishToMavenLocal
```

Versions: `0.1.0-SNAPSHOT` (see each module's `build.gradle.kts`).

### Step 3 (Pentest-Swarm only): Build the Go binary

```bash
cd Pentest-Swarm-AI/
make build-android-arm64     # → bin/pentestswarm-android-arm64
make build-android-x86_64    # → bin/pentestswarm-android-x86_64
cp bin/pentestswarm-android-arm64 android/app/src/main/res/raw/pentestswarm_arm64
cp bin/pentestswarm-android-x86_64 android/app/src/main/res/raw/pentestswarm_x86_64
```

These files are in `.gitignore` and must be present before the APK assembles.

### Step 4: Assemble the companion APK

```bash
# HackerAI companion
cd hackeraiETC/android/
./gradlew :app:assembleDebug

# Pentest-Swarm companion
cd Pentest-Swarm-AI/android/
./gradlew :app:assembleDebug
```

### Step 5: Sign and install both APKs with the same key

Both APKs and DCA itself must be signed with the same certificate for
`protectionLevel="signature"` to work. In development, use the same debug
keystore for all three. In production, use the same release keystore.

## AIDL Contract Sync Rule

The AIDL files in each companion repo are **copies** of the canonical files in
`core-companion/src/main/aidl/`. The canonical files are:

- `core-companion/src/main/aidl/ai/droidcommand/companion/ICompanionService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/IHackerAIService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/IPentestSwarmService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/CompanionCapabilityParcel.aidl`

When modifying any AIDL file, update all three locations atomically:

1. Edit `core-companion/src/main/aidl/...` (source of truth)
2. Copy to `hackeraiETC/android/app/src/main/aidl/...`
3. Copy to `Pentest-Swarm-AI/android/app/src/main/aidl/...`

A diverged AIDL file causes a `RemoteException: Binder interface mismatch`
at bind time, which is not caught at compile time.

## Capability Lifecycle

### Adding a new companion capability

1. Add the method to the relevant `.aidl` file (sync all three copies).
2. Add the `@Serializable` input/output types to `core-hackerai` or
   `core-pentest-swarm`.
3. Implement the method in the companion's bound service (`HackerAIBoundService`
   or `PentestSwarmBoundService`) — public delegate method + binder delegation.
4. Implement the DCA-side `Tool` in `core-hackerai.HackerAiTool` or add a new
   `SwarmTool.*` in `core-pentest-swarm`.
5. Register the tool in `cli/Main.kt`'s tool registry.
6. Add JVM tests (mockk) for the bound service method and the DCA tool.

### Detecting companion presence at runtime (DCA side)

`CompanionCapabilityHealthChecker` (in `core-companion`) calls `ping()` via
the bound service interface. It returns:
- `AVAILABLE` — companion installed + `ping()` succeeds
- `REQUIRES_EXTERNAL_SERVICE` — companion not installed
- `ERROR` — companion installed but not responding

`suggestedReverifyIntervalMs()` returns `30_000L`. The `CapabilityManager` in
`core-security` re-runs the check on this schedule.

## Testing Strategy

### JVM-only (runs without device)

All `core-hackerai` and `core-pentest-swarm` tests. Run with:

```bash
./gradlew :core-hackerai:test :core-pentest-swarm:test
```

These cover: JSON schema validation, catalog loading, doom loop detection,
runtime recovery, step budget gating, HTTP client URL construction and JSON
parsing (via `MockHttpTransport`).

Each companion APK's `src/test/` directory also has JVM tests (mockk +
Robolectric) that run without a device:

```bash
# In hackeraiETC/android/
./gradlew :app:test

# In Pentest-Swarm-AI/android/
./gradlew :app:test
```

### Requires Android device (deferred — NOT RUNTIME VERIFIED)

The following have never been tested on a real device and are correctly marked
as such in the audit doc:

- AIDL binding between DCA and a companion APK
- `protectionLevel="signature"` rejection of a third-party bind attempt
- Go binary extraction and execution on ARM64 hardware
- Per-session API key injection and validation end-to-end
- Termux `RUN_COMMAND` intent delivery
- Full end-to-end: DCA dispatches a `SwarmTool` → AIDL → companion → Go HTTP →
  findings returned → `SecureToolExecutor` audit log entry

All of these require two APKs signed with the same key installed on a real
(or emulated) Android device. This environment has no Android SDK and no device.

## Module Dependency Map

```
app (Android)
  ├── core-companion (Android, excluded from JVM build)
  │     └── core-agent, core-security
  ├── core-hackerai (JVM)
  │     └── core-agent, core-security, kotlinx-serialization
  └── core-pentest-swarm (JVM)
        └── core-agent, core-security, core-remote, kotlinx-serialization, yamlkt

hackeraiETC/android/ (Android, standalone)
  └── core-hackerai via mavenLocal()

Pentest-Swarm-AI/android/ (Android, standalone)
  └── core-pentest-swarm via mavenLocal()
```
