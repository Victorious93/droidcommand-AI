plugins {
    kotlin("jvm") version "2.4.10" apply false
    // Declared here (apply false) so the Android, Kotlin, KSP and Hilt plugins share one classloader;
    // loading them separately in :app / :core-conversations fails with NoClassDefFoundError
    // (com/android/build/gradle/BaseExtension). Versions come from settings.gradle.kts.
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.google.devtools.ksp") apply false
    id("com.google.dagger.hilt.android") apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

repositories {
    mavenCentral()
}

// ROADMAP-119: no Kotlin lint was configured anywhere in this repository.
// Applied at the root rather than repeated in each module's build file
// (unlike the per-module kotlin("jvm") + jvmToolchain declarations) because
// lint configuration, unlike a module's own dependencies/toolchain, has
// nothing module-specific to say — every subproject gets the same rules.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
