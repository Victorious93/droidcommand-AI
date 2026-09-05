package ai.droidcommand.build

/**
 * A generic classification of what a build should produce. A future
 * platform-specific [BuildExecutor] maps this (plus [ProjectType]) to its
 * own concrete steps — e.g. an Android executor mapping (ANDROID, RELEASE)
 * to the Gradle task `assembleRelease`, or (ANDROID, PACKAGE) to
 * `bundleRelease` — without [BuildTarget] itself growing an
 * Android-specific value.
 */
enum class BuildTarget {
    DEBUG,
    RELEASE,
    TEST,
    PACKAGE,
    ARTIFACT,
}
