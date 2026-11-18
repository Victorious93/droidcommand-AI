// Pure-JVM half of "download, install and keep fleet apps up to date": release JSON parsing, version
// comparison, APK choice, download-source rules and SHA-256 verification. The Android half (HTTP download,
// PackageInstaller session, WorkManager check) lives in :app and is the only code that touches the phone.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
