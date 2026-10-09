// Phase 5 (Consumer Product Roadmap) — Android half of Document Q&A: Room-backed vector store/document
// library and the PDF text extractor. Opt-in via includeAndroid (needs an Android SDK), like :core-conversations.
// Tested with Robolectric on the JVM; never run on a device.
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
    namespace = "ai.droidcommand.rag.android"
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
    api(project(":core-rag"))
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Apache-2.0 port of Apache PDFBox for Android (com.tom-roush, Maven Central, last release 2.0.27.0).
    // The platform PdfRenderer only exposes text on very new Android versions, so this is the portable route.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
