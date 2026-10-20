# Security requirements — DroidCommand AI

This document exists because a detailed Codex engineering brief (stored
verbatim in this repo's git history as the commit that added this file —
see `git log -- docs/SECURITY.md`) specified concrete Android security
requirements — storage boundaries, networking, secure tool execution,
secrets, logging — that this repo did not yet have written down in one
place. It reconciles that brief against what's **already implemented**
here so a future session builds on the existing `core-security`/
`core-shell` stack instead of reinventing it, and states plainly which
requirements are **not yet applicable** because the Android `:app` module
itself is still PLANNED (no Android SDK exists in this JVM-only
environment; see `CLAUDE.md`).

Treat this as a durable requirements/design doc, updated as each item
below moves from PLANNED to IMPLEMENTED — not a one-time audit output.

## Already implemented (JVM-side, provider/target-agnostic)

These exist today in `core-security`/`core-agent`/`core-shell` and are
the foundation every Android-specific piece below must plug into, not
duplicate:

- **Controlled tool execution boundary** — `SecureToolExecutor` wraps
  `ToolExecutor` with a `SecurityPolicyEnforcer` check that runs *before*
  a tool is ever invoked; a `Deny` never reaches the tool. This is
  already the "Input → Parse → Validate → Allowlist → Permission Check →
  Confirmation → Execute → Result" pipeline the brief asks for, expressed
  as `mode` check → `Initiator` check → `SecurityPolicyEnforcer.authorize`
  (deny/require-approval/allow) → grant check → audit record → delegate.
- **Default-deny confirmation with binding and timeout** — `ApprovalFlow`
  (`RiskTier`: `READ_ONLY`/`REVERSIBLE`/`DESTRUCTIVE`/`IRREVERSIBLE`,
  `ApprovalRequest`/`ApprovalResponse`, `RiskApprovalPolicy`) already
  distinguishes `Denied` from `TimedOut` from `Unavailable` so a caller
  can default-deny on each correctly, with per-tier default timeouts.
  `ApprovalRequest.requestId` exists for binding a decision to one
  specific request. The model never approves its own request — approval
  always routes through `ApprovalProvider`/`approvalPrompt`, a caller-
  supplied implementation, never the planner.
- **Registry-only execution, typed tool metadata** — `ToolRegistry` +
  `ToolSpec` (`requiredPermissions`, `requiresRoot`, `securityLevel`,
  `requiresConfirmation`, `allowedModes`, `grantCapability`,
  `requiredInitiator`, `permissionCategory`). Only a registered `Tool` can
  run; `ObjectiveEngine`'s planner can only select from
  `registry.list(mode, Initiator.AI)`.
- **One-time grants** — `GrantStore`/`GrantCheck` (`Live`/`Denied`),
  consumed only after the delegate call actually succeeds, never merely
  once every other gate passes — a failed attempt never burns a
  single-use grant.
- **Fail-closed audit trail** — `AuditLog`/`InMemoryAuditLog`: a
  sensitive/root-level invocation that can't be recorded is denied rather
  than run unaudited (bounded capacity, refuses new entries at capacity
  rather than silently evicting old ones — an audit trail that drops
  entries could hide the exact action it exists to prove happened).
  `AuditEventType` already includes `SECRET_ACCESSED`/`SECRET_REVOKED`
  alongside the access/grant/approval events.
  *Updated 2026-10-08p:* `:app` now wires a `JsonFileAuditLog` (app-private `filesDir/audit/audit.jsonl`)
  into its `SecureToolExecutor` and the Plan runner's per-run executors, and shows it read-only in a Logs
  screen. Append-only by design, so the app offers no clear button; at the default 10,000-event capacity
  it fails closed (sensitive actions refused). Not run on a device.
- **Least-privilege execution modeling** — `Initiator`
  (`AI`/`DEVICE_OWNER`/`REMOTE`, a *policy* boundary, not cryptographic —
  see its own doc comment), `PermissionCategory` +
  `ROOT_EQUIVALENT_CATEGORIES` (root-equivalent categories additionally
  require `rootEnabled && rootAvailable()`, fail-closed), `RiskTier`,
  `EscalationTier` (lowest-to-highest privilege ordering, with the
  brief's own caveats about `TERMUX`/`SHIZUKU` not being privilege tiers
  themselves preserved in the type's own doc comment).
- **Live, re-verifiable capability state** — `CapabilityRegistry`/
  `CapabilityMetadata`/`CapabilityState` (11 states, including
  `REQUIRES_PERMISSION`/`REQUIRES_ROOT`/`REQUIRES_CONFIGURATION`) —
  capabilities are not detected once and cached forever.
- **No shell-string construction from model output** —
  `core-shell.ShellCommand`/`ShellSecurityPolicy`/`ShellTool`/
  `ProcessBuilderShellExecutor` already model command execution as a
  fixed executable + argument list, not string concatenation; review
  `ShellSecurityPolicy` when extending it rather than adding a second,
  competing execution path.
- **Fail-closed target abstraction** — `ExecutionTarget`
  (`isHealthy`/`execute`, `PrivilegeLevel`, `ExecutionResult.verified`
  honestly `false` everywhere today — no target re-queries state to
  confirm an exit code reflects reality yet; that's real follow-up work,
  not fabricated).

## Not yet applicable — gated on the Android `:app` module (PLANNED)

The brief's storage, networking, and secrets requirements below assume
Android-specific APIs (`Context.filesDir`, Storage Access Framework,
Android Keystore, Network Security Configuration, `TextToSpeech`-adjacent
manifest/permission handling) that only exist once an Android SDK build
target exists. Per `CLAUDE.md`, that is Consumer Roadmap **Phase 0**
(Android App Shell), status NOT STARTED, and this JVM-only environment
cannot build or verify Android code today. These requirements are real
and should be honored **when Phase 0+ lands**, not retrofitted onto the
JVM-only core modules now:

### Storage boundaries
- Default file tools to `filesDir`/`cacheDir`/`noBackupFilesDir` or another
  app-private directory the tool explicitly selects.
- Canonicalize every path; reject traversal/symlink escapes; require the
  resolved path stay under an approved root.
- Never expose databases, SharedPreferences, Keystore material, APKs, or
  native libraries through a tool unless explicitly allowlisted.
- User-selected external documents only via SAF (`ACTION_OPEN_DOCUMENT`/
  `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE`); persist URI
  permissions only after explicit user selection, for the minimum
  required mode, released when no longer needed; validate scheme/
  authority/MIME/flags before every operation — never convert an
  arbitrary URI into a raw filesystem path.
- Temporary exports live in app-private cache, deleted after use.
- **`:app` backup — IMPLEMENTED 2026-10-15.** `android:allowBackup="false"`, `android:fullBackupContent="false"`
  (Android 9–11) and `res/xml/data_extraction_rules.xml` (Android 12+) exclude every domain from cloud backup
  and device-to-device transfer, so chats, the memory graph, notes, clipboards, the audit log and imported
  models are never copied off the device by the platform. The cost: moving to a new phone starts empty until
  an export feature exists (Keystore-bound secrets could not be restored anyway). `ManifestPolicyTest` pins
  all three. Before this, the merged manifest of the debug APK carried `allowBackup=true` with no rules.

### Networking
- HTTPS by default; Network Security Configuration disabling cleartext
  and restricting trust — no permissive trust managers/hostname
  verifiers.
- `INTERNET` permission only when required.
- Certificate/hostname validation, bounded timeouts, cancellation
  propagation, response-size limits, no main-thread networking.
- Per-tool allowlisted hosts/schemes/ports/methods/redirect behavior — no
  arbitrary destinations, loopback/local-network probing, or metadata
  endpoint access.
- Retry only safe/idempotent failures, bounded, with backoff; never retry
  auth or confirmation failures.
- **`:app` network security config — IMPLEMENTED 2026-10-15.** `res/xml/network_security_config.xml` sets
  `cleartextTrafficPermitted="false"` and trusts only the system certificate store; `ManifestPolicyTest` fails
  if either changes or a `domain-config` exception is added without review. Not covered: certificate pinning
  and mutual TLS (only `JdkHttpTransport` has them, and it cannot run on Android), response-size limits
  (`HttpUrlConnectionTransport` reads a whole body into memory), and cancellation of an in-flight request
  when a screen is left (it ends at the read timeout).
- **Package visibility — IMPLEMENTED 2026-10-15.** The app asks Android about named packages
  (`<queries><package …>`), never `QUERY_ALL_PACKAGES`. `ManifestPolicyTest` keeps the list in step with
  `FleetCatalog` plus the Shizuku/Sui manager packages (`moe.shizuku.privileged.api`, `rikka.sui`)
  and fails if the broad permission appears.

### Secrets
- Provider credentials in Android Keystore-backed encrypted storage
  (`KeystoreSecretsVault`, already named as Phase 1 work in `CLAUDE.md`'s
  Consumer Roadmap — this is the same requirement, not a new one).
  `core-config.SecretsVault`/`VaultBackedConfigSource` already exist as
  the JVM-side interface (CAP-013); the Keystore-backed implementation is
  the Android-specific piece still to build.
- Never in prompts, tool arguments, logs, or error messages.
- **Update 2026-10-15 (the paragraph above is older):** the Keystore-backed vault exists
  (`app/security/AndroidSecretsVault.kt`, AES-256-GCM key in the AndroidKeyStore, wired in `AppModule`) and
  its read path (`inspect`) ran on an API 28 emulator. Saving and decrypting a real key has still never run on
  a device. Typed API keys are deliberately kept out of saved instance state (`SettingsScreen.ApiKeyRow`);
  the new `rememberDraft` helper is for non-secret text only. Private clips copied to the system clipboard are
  now flagged `EXTRA_IS_SENSITIVE` (Android 13+ hides them from the on-screen preview); that does not stop another
  app in the foreground from reading the clipboard.

- **Update 2026-10-18 — key protection level and secret access.** The vault's key is created in the
  AndroidKeyStore, trying StrongBox first and falling through to the platform default (a trusted
  execution environment where the device has one, otherwise a software-backed keystore key).
  `EncryptedSecretsVault.protectionLevel()` reports which one the existing key actually is
  (`KeyProtectionLevel`: `STRONGBOX`, `TRUSTED_ENVIRONMENT`, `SOFTWARE`, `UNKNOWN`).
  **Hardware-backed strength depends on the device**: emulators and some phones end up `SOFTWARE`, and
  `UNKNOWN` must be read as "not shown to be hardware-backed". Fallback behaviour, stated plainly: there
  is no fallback to a key in app memory, app files or plaintext. If the keystore is unusable, `putSecret`
  throws `SecretStorageException` and `getSecret` returns `null` / `inspect` says `Unrecoverable`, so the
  user re-enters the key. An existing key is never regenerated to upgrade its level (that would orphan the
  stored records). `useSecret(id) { bytes -> }` gives the value as a byte array that is zeroed afterwards
  and never becomes a `String` inside the vault; callers that copy it into a `String` lose that benefit,
  and `getSecret` still returns an ordinary `String`. A Keystore `ProviderException` (broken or wiped
  keystore) is now treated like a lost key instead of escaping as a crash. Release-signing keys are out of
  scope and stay in CI secrets. Not run on a device; the `KeyInfo` read is unverified on real hardware.

### Logging
- Structured events (request id, tool name, outcome, duration, coarse
  error category) — never secrets, auth headers, cookies, full file
  contents, URI grants, or raw prompts containing credentials. Redact
  paths/query params/payloads/identifiers. Disable verbose logs in
  release builds.

### UI confirmation surface
- Every dangerous-operation confirmation must show the exact tool,
  target, scope, data destination, and arguments — never a generic
  "Allow" with hidden detail — and must not treat prior conversational
  consent as approval for a materially different later request. This is
  the UI layer on top of `ApprovalFlow`'s already-real `ApprovalRequest`/
  `ApprovalResponse` types. `:app`'s `ComposeApprovalPrompt` dialog is the
  surface (2026-10-09g: now per-request, cancellable and serialized).
- **Voice approvals (2026-10-09g).** Optional, OFF by default. When on, the
  request is read aloud and the user may say "deny"; **voice can never
  approve in this build** — `VoiceApprovalPrompt` classifies every request
  `RiskTier.DESTRUCTIVE`, outside `VoiceApprovalProvider.VOICE_APPROVABLE`
  (owner decision). Fail closed on deny/timeout/unavailable/error; voice
  decisions are audited before they are returned. With the setting off the
  gate is unchanged. Known limits: no speaker verification; the voice path
  adds a 120 s timeout the screen-only path does not have; not exercised
  with a real microphone.
- **Shizuku / Sui (CAP-018 first slice, 2026-10-19e).** Privileged ADB-shell
  ops (`run_shizuku_command`, `shizuku_read_device_state`, `shizuku_pm_grant`)
  never auto-run. They are `SENSITIVE` / `DEVICE_CONTROL`, always go through
  `SecureToolExecutor` → `ApprovalPrompt`, and their names contain `shizuku`
  so `ActionRiskClassifier` hard-gates them. YOLO may waive Shizuku the same
  way it may waive shell/root/Termux; it still cannot skip Kali / Metasploit /
  SET / Pentest-Swarm authorization (the phrase "I confirm these targets are
  authorized" and per-step ACTIVE/EXPLOIT prompts). Command stdout/stderr is
  never logged. Missing or not-running Shizuku degrades to unprivileged use;
  it does not crash or bypass confirmation. Not run against live Shizuku/Sui.
- **Remote Control skeleton (2026-10-20, protocol RATIFIED 2026-10-20c).**
  SMS (VictorSMS) and voice triggers share one mapping pipeline. An inbound
  command is an **untrusted request**: VictorSMS whitelist + code word is
  delivery, not authorization. The controller re-applies pairing →
  external-control (OFF default) → nested `auth` replay → policy.
  Envelope anti-replay fields are `auth { nonce, issuedAt, ttlMs }` (not
  top-level). Source identity is package+cert (`sendingUid`);
  `trigger.sourceNumber` / `matchedKeyword` / a forged `destructive:false`
  cannot authorize. Replay is a seen-nonce cache (`replay_detected` /
  `expired`). Tool names contain `remote`, so they are never-auto-approve
  like shell/shizuku/root. YOLO may waive **non-destructive** remote
  commands (including VictorSpoof registry entries) the same way it waives
  shell; it must **not** waive WIPE / POWER_OFF / LOCK / flash / bootloader
  (`remote_wipe` / `remote_power_off` / `remote_lock` /
  `remote_destructive`) and must not waive Kali/pentest ACTIVE/EXPLOIT
  gates or the phrase "I confirm these targets are authorized" (those
  pentest tokens are unchanged). A remote trigger never auto-approves and
  never auto-sends. Live wipe, power-off, lock, and RustDesk are not
  implemented — the executor is a stub after `ApprovalPrompt`. Spec:
  `docs/specs/REMOTE_CONTROL.md`.

## Design note: per-tool `validate()` vs. externalized policy

The brief's example `Tool` interface splits `execute` from a separate
`suspend fun validate(...): ValidationResult`. This repo's actual `Tool`
interface (`core-agent/Tool.kt`) has only `execute` — validation
(mode/initiator/policy/grant checks) lives externally in
`SecureToolExecutor`, not per-tool. Both are legitimate designs; this is
flagged as a real difference to resolve deliberately (e.g., should a tool
also validate its own `input: Map<String, String>` shape before
`execute`, on top of `SecureToolExecutor`'s policy-level checks?) rather
than silently adopting one over the other. Not resolved by this
document.

## Verification checklist (run when the relevant module exists)

- [ ] Tool allowlisting / registry integrity — **covered today** by
      `ToolRegistry` + `SecureToolExecutor`.
- [ ] Canonical path validation, symlink escape rejection — **not yet
      implemented**; no filesystem tool exists yet in `core-tools-android`
      beyond stubs.
- [ ] SAF URI scheme/authority/grant/boundary validation — **not yet
      implemented**; no Android SDK to build against.
- [ ] Dangerous-operation confirmation, approval expiry, request binding,
      denial/cancellation — **covered today** by `ApprovalFlow` at the
      JVM layer; on-screen dialog exists in `:app` (builds; never run on a
      device); optional voice-deny added 2026-10-09g (see above).
- [ ] API-key/secret handling via Keystore, prompt exclusion, redacted
      transport — **partially covered** (`SecretsVault` interface exists;
      Keystore-backed implementation is Phase 1 work).
- [ ] Network host/scheme/method/timeout/redirect/response-size/retry
      restrictions — **not yet implemented**; no network tool exists yet.
- [ ] Tool sandbox restrictions and resource limits — **partially
      covered** by `ExecutionTarget`'s typed contract; no sandboxed
      subprocess implementation exists yet beyond `core-shell`'s
      fixed-executable model.
- [x] `:app` backup exclusion, cleartext refusal and package-visibility policy
      pinned by a JVM test — **covered 2026-10-15** (`ManifestPolicyTest`).
- [x] `:app` unit tests and ktlint run in CI — **covered 2026-10-15**
      (`.github/workflows/android-checks.yml`; before that no workflow ran them).
      Android Lint is not gated: it needs the native build for every module.
