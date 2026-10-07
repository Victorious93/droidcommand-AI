// Phase 1 (Consumer Product Roadmap) — Room-backed ConversationStore.
//
// NOT INCLUDED in settings.gradle.kts, for the same reason as :app: this
// environment has no Android SDK/AGP. NEVER BUILT OR TESTED — the plugin and
// Room versions below are best-effort coordinates to be confirmed by a real
// Android Studio sync. Do not mark this module IMPLEMENTED until
// `./gradlew :core-conversations:test` (and a connectedAndroidTest for the
// DAO) has actually passed.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.droidcommand.conversations"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":core-agent"))
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation(kotlin("test"))
    // Real Room + SQLite on the JVM (no emulator): tests the DAO SQL, cascade delete and transaction.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

kotlin {
    jvmToolchain(21)
}

// Room schema export (exportSchema = true) needs a location; committed schemas are the basis for
// future migration tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
