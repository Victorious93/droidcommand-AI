// Phase 0 (Consumer Product Roadmap) — the Android UI shell module.
//
// NOT INCLUDED in settings.gradle.kts's active `include(...)` list, matching
// this repository's existing, already-documented convention for `:app`:
// this environment has no Android SDK/AGP, so including it would make the
// root build unbuildable for every other module. Gradle never evaluates a
// module that isn't included, so build-verifying this file requires opting
// in via `includeAndroid=true` (see settings.gradle.kts) with a real SDK.
//
// 2026-10-08: build-verified for real (`./gradlew :app:assembleDebug`,
// `:app:testDebugUnitTest`, `:app:lintDebug`, all BUILD SUCCESSFUL — AGP
// 8.13.2, SDK platforms 35/36 installed) in a session that had a real
// Android SDK available. Dependency versions below are now real, resolved,
// build-verified coordinates — not best-effort guesses — current as of
// that date, bumped as far as AGP 8.13.2 / compileSdk 36 allow (see the
// per-dependency comments below for the exact ceiling each one hit).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x requires the Compose compiler Gradle plugin (matched to the Kotlin version).
    id("org.jetbrains.kotlin.plugin.compose")
    // KSP versions are independent of the Kotlin version since 2.3.x (2.3.12 was the latest on
    // Maven Central when checked, 2026-10-07). Compatibility with Kotlin 2.4.10 is UNVERIFIED.
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

repositories {
    google()
    mavenCentral()
    // The prebuilt sherpa-onnx AAR is a local file (fetched + SHA-256-verified by
    // core-voice-neural-android/scripts/fetch-sherpa-onnx.sh); a module's own repositories{} block is not
    // inherited by its consumers, so this app has to be able to resolve it too.
    flatDir { dirs("../core-voice-neural-android/libs") }
}

android {
    namespace = "ai.droidcommand.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.droidcommand.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    // The device-free core modules this app is a UI shell around — no
    // Android-only reimplementation of any of these, per the Consumer
    // Roadmap's "preserve all existing modules" rule.
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
    implementation(project(":core-config"))
    implementation(project(":core-conversations"))
    implementation(project(":core-rag-android"))
    implementation(project(":core-llm-local-android"))
    implementation(project(":core-knowledge-android"))
    implementation(project(":core-llm"))
    implementation(project(":core-remote"))
    implementation(project(":core-llm-factory"))
    implementation(project(":core-websearch"))
    implementation(project(":core-voice-android"))
    implementation(project(":core-voice-neural-android"))
    implementation(project(":core-shell"))
    implementation(project(":core-root"))
    implementation(project(":core-termux"))
    implementation(project(":core-tools-metasploit"))
    implementation(project(":core-tools-setoolkit"))

    // Phase 3/4 — companion capability modules.
    // core-hackerai and core-pentest-swarm provide the JVM-side Tool stubs and
    // catalog types; core-companion (Android library, also excluded from
    // settings.gradle.kts) provides the AIDL contracts and registry that wire
    // those stubs to the live companion APKs at runtime.
    implementation(project(":core-hackerai"))
    implementation(project(":core-pentest-swarm"))

    // compose-bom, navigation-compose, lifecycle-viewmodel-compose, core-ktx and
    // hilt-navigation-compose are each pinned one or two minors below Maven Central's latest
    // stable release (confirmed 2026-10-08): the next release of every one of them ships an
    // `-android` artifact whose AAR metadata demands compileSdk 37 + AGP 9.1.0, and this project
    // is on AGP 8.13.2 (compileSdk 36 is that AGP's own documented max). Bumping any of these
    // past the versions below fails `:app:checkDebugAarMetadata` in this environment — verified
    // directly, not assumed. Re-check after an AGP 9 upgrade.
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Chat tab icon (Icons.Filled.Chat) is not in the core icon set; version managed by the Compose BOM.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")

    // Hilt 2.60.1 is NOT blocked by AGP 9 (an earlier comment on the kotlin-metadata-jvm force
    // below claimed it was — confirmed stale/incorrect 2026-10-08: 2.60.1 builds clean against
    // AGP 8.13.2 here). hilt-navigation-compose stays at 1.3.0 for the same compileSdk-37 reason
    // as the block above: 1.4.0 pulls in androidx.hilt:hilt-lifecycle-viewmodel-compose, which
    // needs AGP 9. That one version gap is also why `hiltViewModel()` still resolves to the
    // androidx.hilt.navigation.compose (not androidx.hilt.lifecycle.viewmodel.compose) overload,
    // producing a harmless deprecation warning at every call site until the AGP 9 upgrade.
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// Hilt bundles a kotlin-metadata-jvm that reads metadata only up to Kotlin 2.3; this module is
// compiled with Kotlin 2.4. Forcing a newer metadata reader is the workaround Hilt's own error
// message names. Still required with Hilt 2.60.1 (verified 2026-10-08).
configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
}
