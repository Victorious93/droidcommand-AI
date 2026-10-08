// Phase 3 (Consumer Product Roadmap) — Room-backed TemplateStore / SkillStore.
// Opt-in via includeAndroid (needs an Android SDK). Mirrors :core-conversations.
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
    namespace = "ai.droidcommand.templates.android"
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
    api(project(":core-templates"))
    // Skill.preferredProviderType is a core-llm type; core-templates only declares it as implementation.
    api(project(":core-llm"))
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation(kotlin("test"))
    // Real Room + SQLite on the JVM (no emulator).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

// Committed schemas are the basis for future migration tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
