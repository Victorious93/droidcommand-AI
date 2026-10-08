// Phase 2 (Consumer Product Roadmap) — native llama.cpp build for ProviderType.LOCAL.
// Opt-in via includeAndroid (needs an Android SDK + NDK). Phase B: builds llama.cpp only;
// no JNI shim, no InferenceBackend implementation, no inference yet.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.droidcommand.llm.local.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            // armeabi-v7a is deliberately not listed until a build of it is proven.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":core-llm-local"))
}

kotlin {
    jvmToolchain(21)
}

// Fetch the pinned llama.cpp before any native configure/build. The script is idempotent and
// verifies the commit hash, so repeated builds are a no-op.
val fetchLlamaCpp by tasks.registering(Exec::class) {
    commandLine("bash", "$projectDir/scripts/fetch-llama-cpp.sh")
}
tasks.matching { it.name.startsWith("configureCMake") || it.name == "preBuild" }.configureEach {
    dependsOn(fetchLlamaCpp)
}
