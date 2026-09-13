plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-config"))
    implementation(project(":core-remote"))
    implementation(project(":core-llm-factory"))
    implementation(project(":core-security"))
    implementation(project(":core-shell"))
    implementation(project(":core-root"))
    implementation(project(":core-termux"))
    implementation(project(":core-build"))
    implementation(project(":core-build-local"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ai.droidcommand.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
