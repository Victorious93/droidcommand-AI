// Pure-JVM half of the on-device Messenger transport for EXTERNAL_NODE fleet members. Holds the
// envelope handling, response validation, package+signing-cert trust check and pairing model; the
// Android Messenger plumbing that implements MessengerChannel lives in :app. No Android dependency.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // The envelope (BridgeRequest/BridgeResponse), FailureKind and RootForgeResult are the shared contract
    // and appear in this module's public API, so they are exposed rather than copied.
    api(project(":core-rootforge"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
