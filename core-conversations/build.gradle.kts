// Phase 1 (Consumer Product Roadmap) — Room-backed ConversationStore.
//
// NOT INCLUDED in settings.gradle.kts, for the same reason as :app: this
// environment has no Android SDK/AGP. NEVER BUILT OR TESTED — the plugin and
// Room versions below are best-effort coordinates to be confirmed by a real
// Android Studio sync. Do not mark this module IMPLEMENTED until
// `./gradlew :core-conversations:test` (and a connectedAndroidTest for the
// DAO) has actually passed.
plugins {
    id("com.android.library") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("com.google.devtools.ksp") version "2.4.10-1.0.28"
}

android {
    namespace = "ai.droidcommand.conversations"
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
    api(project(":core-agent"))
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
