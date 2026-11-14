// Phone-worker half of the VictorLinux fleet coordinator contract (protocol_major 1). Pure JVM: the Android
// Keystore identity, WorkManager/foreground-service plumbing and capability probes live in :app and implement
// the interfaces here. Everything in this module is tested without a device.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core-agent"))
    implementation(project(":core-remote"))
    implementation(project(":core-security"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Optional cross-repo integration test: point at VictorLinux/fleet to run the real coordinator.
    environment("VICTOR_FLEET_LIB", System.getenv("VICTOR_FLEET_LIB") ?: "")
}
