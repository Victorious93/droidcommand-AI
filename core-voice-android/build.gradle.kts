// Phase 4 (Consumer Product Roadmap) — SpeechRecognizer / TextToSpeech wrappers for :core-voice's seams.
// Opt-in via includeAndroid (needs an Android SDK). NEVER BUILD-VERIFIED — see the 2026-10-09c audit addendum.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.droidcommand.voice.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":core-voice"))
}

kotlin {
    jvmToolchain(21)
}
