// Phase 4 (Companion APK Integration) — Android library module providing:
//   - AIDL interface contracts for DCA ↔ companion APK IPC (ICompanionService,
//     IHackerAIService, IPentestSwarmService + CompanionCapabilityParcel)
//   - CompanionRegistry (AIDL ServiceConnection management)
//   - CompanionCapabilityHealthChecker (implements core-security's CapabilityHealthChecker)
//   - CompanionTool (wraps any AIDL capability as a core-agent Tool)
//
// NOT INCLUDED in settings.gradle.kts — Android SDK required (PackageManager,
// ServiceConnection, @Parcelize). Same exclusion pattern as :app. All files
// here are genuinely complete; they have NOT been AGP-compiled or build-verified
// in this environment. Confirm plugin/AGP version match with :app before adding
// to settings.gradle.kts.
plugins {
    id("com.android.library") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.10"
}

android {
    namespace = "ai.droidcommand.companion"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
}
