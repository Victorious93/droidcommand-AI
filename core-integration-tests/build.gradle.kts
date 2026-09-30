plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // No src/main: this module exists solely to hold cross-module
    // integration tests that need two sibling modules (core-shell,
    // core-root) together, which neither depends on the other and no
    // existing module depends on both. testImplementation only, since
    // there is no production API of its own for anything to consume.
    testImplementation(project(":core-agent"))
    testImplementation(project(":core-security"))
    testImplementation(project(":core-shell"))
    testImplementation(project(":core-root"))
    // Developer-assistance features (docs/DEV_ASSIST.md): edit_file + git checkpoints, tested together.
    testImplementation(project(":core-code-edit"))
    testImplementation(project(":core-git"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
