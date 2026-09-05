package ai.droidcommand.build

data class BuildContext(
    val request: BuildRequest,
    val workspace: WorkspaceHandle,
    val sourceDir: String,
)

sealed class BuildExecutionResult {
    data class Success(
        val exitStatus: Int?,
        val output: String,
        val artifacts: List<Artifact>,
        val warnings: List<String> = emptyList(),
    ) : BuildExecutionResult()

    data class Failure(
        val exitStatus: Int?,
        val output: String,
        val error: BuildError,
    ) : BuildExecutionResult()
}

/**
 * The only point at which a build actually "happens." [core-build] itself
 * never spawns a process or invokes a compiler — that is deliberately left
 * to an implementation of this interface, so the core layer stays usable
 * in a headless environment with no Android SDK, JDK build toolchain, or
 * device. Future implementations (none exist in this repository yet):
 *
 * - `AndroidGradleBuildExecutor` — invokes the Android Gradle Plugin.
 * - `LocalProcessBuildExecutor` — runs an arbitrary local build command.
 * - `RemoteBuildExecutor` — delegates to a build server over
 *   `core-remote`'s `RemoteClient`, consuming the same [BuildContext] /
 *   [BuildExecutionResult] contract so the agent never needs to know
 *   whether a build ran locally or remotely.
 *
 * [MockBuildExecutor] is the only implementation in this phase, and it
 * never performs a real build.
 */
interface BuildExecutor {
    fun execute(context: BuildContext, isCancelled: () -> Boolean = { false }): BuildExecutionResult
}
