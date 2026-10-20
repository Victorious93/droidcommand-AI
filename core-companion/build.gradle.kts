// Phase 4 (Companion APK Integration) — Android library module providing:
//   - AIDL interface contracts for DCA ↔ companion APK IPC (ICompanionService,
//     IHackerAIService, IPentestSwarmService, IEdlCompanion + CompanionCapabilityParcel)
//   - CompanionRegistry (AIDL ServiceConnection management)
//   - CompanionCapabilityHealthChecker (implements core-security's CapabilityHealthChecker)
//   - CompanionTool (wraps any AIDL capability as a core-agent Tool)
//
// Opt-in via includeAndroid (needs an Android SDK), like :app. Plugin versions come from the
// pluginManagement block in settings.gradle.kts. First compiled by the Fleet Orchestrator PR; it has
// never run against a real companion APK.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.droidcommand.companion"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
}

kotlin {
    jvmToolchain(21)
}
