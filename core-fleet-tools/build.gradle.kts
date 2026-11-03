// Real LLM-callable tools for EXTERNAL_NODE fleet members whose live transport is the generic
// Messenger client in core-fleet-messenger (no device-free/SSH fallback exists for these members,
// unlike RootForge). Pure JVM: FleetMessengerController and everything it depends on is pure JVM
// too (the Android Messenger plumbing is injected from :app as a MessengerChannel), so this module
// is fully unit-testable with fakes, same as core-fleet-messenger's own tests. No Android dependency.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-fleet-messenger"))
    implementation(project(":core-rootforge"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
