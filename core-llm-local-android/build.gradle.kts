// Phase 2 (Consumer Product Roadmap) — native llama.cpp build for ProviderType.LOCAL.
// Opt-in via includeAndroid (needs an Android SDK + NDK). Phase B built llama.cpp itself;
// Phase C (this) adds the JNI shim and LlamaCppBackend (InferenceBackend). Still no device/
// emulator here, so no inference has actually been run — see docs/AUDIT_2026-09-05.md.
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
        // 28, not 26: ggml-vulkan calls Vulkan 1.1 entry points (vkGetPhysicalDeviceFeatures2) and
        // the NDK's libvulkan.so stub only exports them from API 28. :app (minSdk 26) cannot depend
        // on this module as is; the app is expected to offer local inference only on API 28+.
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // LlamaCppBackend uses core-agent's Message/Role; core-llm-local only declares it as implementation.
    api(project(":core-agent"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
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
