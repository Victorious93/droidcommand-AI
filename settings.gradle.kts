rootProject.name = "DroidCommand-AI"

include(":core-agent")
include(":core-llm")
include(":core-security")
include(":core-config")
include(":core-remote")
include(":core-build")
include(":core-tools-android")
include(":core-shell")
include(":core-apk-lifecycle")
include(":core-root")
include(":core-termux")
include(":core-llm-anthropic")
include(":core-llm-openai")
include(":core-llm-factory")
include(":core-build-local")
include(":core-build-remote")
include(":core-mcp")
include(":core-integration-tests")
include(":cli")
include(":core-prompt-regen")
include(":core-tools-metasploit")
include(":core-tools-setoolkit")
include(":core-hackerai")
include(":core-pentest-swarm")
include(":core-llm-google")
include(":core-llm-groq")

// The following modules are part of the target architecture (see
// docs/ARCHITECTURE.md) but are intentionally not included in the build to
// avoid an unbuildable root:
//   :app (Android UI shell — gated on an Android SDK this environment does
//   not have; :cli above is the separate device-free entrypoint named
//   alongside it in docs/AUDIT_2026-09-05.md's addenda). Unlike when this
//   comment was first written, :app now DOES contain real, complete Kotlin/
//   Compose/Hilt source (Phase 0 nav skeleton plus a real Tools screen
//   wired to a live SecureToolExecutor, including the run_metasploit_module/
//   run_setoolkit_attack GUI added for the HackerAI-port security-tooling
//   request) — it is excluded here for the same SDK-availability reason as
//   always, not because it is empty. See the "app module (Phase 0 Compose
//   scaffold)" audit addendum: it has NOT been build-verified in this
//   environment (no `./gradlew :app:assembleDebug` has ever run against it).
//
//   :core-companion (Android library — AIDL contracts + CompanionRegistry/
//   CompanionCapabilityHealthChecker/CompanionTool for DCA ↔ companion APK
//   IPC, added in Phase 4 of the Companion APK Integration). All source is
//   complete, but the module requires Android SDK/AGP to compile (it uses
//   PackageManager, ServiceConnection, @Parcelize). Excluded here for the
//   same SDK-availability reason as :app. See core-companion/build.gradle.kts
//   and docs/AUDIT_2026-09-05.md's "Phase 4" addendum for the full record.
