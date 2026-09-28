// Phase 0 (Consumer Product Roadmap) — the Android UI shell module.
//
// NOT INCLUDED in settings.gradle.kts's active `include(...)` list, matching
// this repository's existing, already-documented convention for `:app`:
// this environment has no Android SDK/AGP, so including it would make the
// root build unbuildable for every other module. Gradle never evaluates a
// module that isn't included, so this file's exact plugin/dependency
// versions have NOT been resolved or build-verified here — they are a
// best-effort, real (not fabricated) starting point using stable, current
// Android/Compose/Hilt coordinates as of this module's authoring, and
// should be confirmed against an actual Android Studio sync (which can
// bump them via the IDE's own upgrade-assistant) before this module is
// added to `settings.gradle.kts` and built for real.
plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("com.google.devtools.ksp") version "2.4.10-1.0.28"
    id("com.google.dagger.hilt.android") version "2.56.2"
}

android {
    namespace = "ai.droidcommand.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.droidcommand.app"
        minSdk = 26
        targetSdk = 35
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

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // The device-free core modules this app is a UI shell around — no
    // Android-only reimplementation of any of these, per the Consumer
    // Roadmap's "preserve all existing modules" rule.
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
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

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test"))
}
