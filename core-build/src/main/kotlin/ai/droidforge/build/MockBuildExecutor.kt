package ai.droidforge.build

/**
 * A safe, non-executing stand-in for a real [BuildExecutor]. It never
 * invokes a compiler, Gradle, or any external process, and its default
 * outcome reports zero artifacts with an output message that says
 * explicitly that no real build occurred — this exists so [BuildPipeline]
 * can be exercised end-to-end in tests without an Android SDK or JDK build
 * toolchain, not to simulate a successful compile. Supply [outcome] to
 * script a specific scenario (failure, a specific set of artifacts) for a
 * particular test; the default is intentionally the least impressive
 * truthful result, not an invented success.
 */
class MockBuildExecutor(
    private val outcome: (BuildContext) -> BuildExecutionResult = ::defaultOutcome,
) : BuildExecutor {
    override fun execute(context: BuildContext, isCancelled: () -> Boolean): BuildExecutionResult {
        if (isCancelled()) {
            return BuildExecutionResult.Failure(
                exitStatus = null,
                output = "",
                error = BuildError.Cancelled("Cancelled before mock execution"),
            )
        }
        return outcome(context)
    }

    companion object {
        fun defaultOutcome(context: BuildContext): BuildExecutionResult = BuildExecutionResult.Success(
            exitStatus = 0,
            output = "MockBuildExecutor: no real build was performed. This is a scaffold result.",
            artifacts = emptyList(),
        )
    }
}
