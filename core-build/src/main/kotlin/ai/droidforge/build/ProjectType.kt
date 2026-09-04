package ai.droidforge.build

/**
 * What kind of project a [BuildRequest] targets. Android is one project
 * type among several, not the shape the pipeline is built around — see
 * docs/ARCHITECTURE.md for why. Adding a new type here does not require
 * changing [BuildPipeline]; only a future [BuildExecutor] implementation
 * needs to know how to actually build one.
 */
enum class ProjectType {
    ANDROID,
    JVM,
    NATIVE,
    GENERIC,
    UNKNOWN,
}
