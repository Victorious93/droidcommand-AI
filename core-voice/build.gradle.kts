plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // ApprovalProvider/ApprovalRequest/AuditLog are part of VoiceApprovalProvider's public surface.
    api(project(":core-security"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
