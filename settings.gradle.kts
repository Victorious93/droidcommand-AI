pluginManagement {
    // Versions for the Android modules' plugins live here (not in each module) because Gradle
    // rejects a version on a plugin whose artifact the root project already loaded (the Kotlin
    // Gradle plugin, via the root `kotlin("jvm") apply false`). Only resolved if a module asks.
    plugins {
        id("com.android.application") version "8.13.2"
        id("com.android.library") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
        id("com.google.devtools.ksp") version "2.3.12"
        id("com.google.dagger.hilt.android") version "2.58"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Only needed when the Android modules are enabled (see includeAndroid below); filtered so
        // the JVM-only build never asks Google's repository for Kotlin/Gradle plugins.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

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
include(":core-llm-google")
include(":core-llm-groq")
include(":core-llm-local")
include(":core-templates")
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
include(":core-rootforge")

// Android modules are opt-in: they need an Android SDK, which JVM-only environments (CI, cloud
// sessions) do not have. In Android Studio, add `includeAndroid=true` to your USER-level
// ~/.gradle/gradle.properties (not committed), then sync. NEVER BUILD-VERIFIED — see
// docs/AUDIT_2026-09-05.md's Phase 0 and 2026-10-07c entries.
if (providers.gradleProperty("includeAndroid").orNull == "true") {
    include(":app")
    include(":core-conversations")
}

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
