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
