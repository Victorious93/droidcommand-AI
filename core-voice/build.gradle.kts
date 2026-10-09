plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // ApprovalProvider/ApprovalRequest/AuditLog are part of VoiceApprovalProvider's public surface.
    api(project(":core-security"))
    // .tar.bz2 model archives (the only per-model download the sherpa-onnx project publishes on GitHub).
    // Apache-2.0; works on Android API 26+.
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
