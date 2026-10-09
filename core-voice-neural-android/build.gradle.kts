// V2 (docs/VOICE_PHASE_SCOPE.md) — sherpa-onnx neural TTS adapters for :core-voice's seams.
// Opt-in via includeAndroid (needs an Android SDK). NEVER BUILD-VERIFIED — see the 2026-10-09e audit addendum.
// The sherpa-onnx AAR is the project's PREBUILT release artifact (pinned + SHA-256 checked by
// scripts/fetch-sherpa-onnx.sh), not built here.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
    // The fetched AAR lives in libs/. Whichever module consumes this one (:app) must also be able to
    // resolve it — see the audit addendum; wiring :app is not done yet.
    flatDir { dirs("libs") }
}

android {
    namespace = "ai.droidcommand.voice.neural"
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
    // Prebuilt release AAR (Apache-2.0): Kotlin API in com.k2fsa.sherpa.onnx + native libs for 4 ABIs.
    api(mapOf("name" to "sherpa-onnx-1.13.8", "ext" to "aar"))
}

kotlin {
    jvmToolchain(21)
}

val fetchSherpaOnnx by tasks.registering(Exec::class) {
    commandLine("bash", "$projectDir/scripts/fetch-sherpa-onnx.sh")
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(fetchSherpaOnnx)
}
