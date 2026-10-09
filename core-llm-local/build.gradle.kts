plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    api(project(":core-rag"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
