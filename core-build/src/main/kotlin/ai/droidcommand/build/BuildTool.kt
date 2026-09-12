package ai.droidcommand.build

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [BuildPipeline] to the agent as a single [Tool], per the
 * preferred chain Agent -> Tool -> Build Service -> Pipeline -> Executor:
 * the agent never talks to [BuildPipeline] directly and never gets a path
 * to invoke a compiler itself. Building is [SecurityLevel.SENSITIVE] by
 * default — it touches the filesystem and, once a real executor exists,
 * spawns external processes — so it is gated by core-security's
 * `SecureToolExecutor`/`SecurityPolicyEnforcer` exactly like any other
 * sensitive tool; this class does not reimplement approval logic.
 * [requestFactory] maps raw tool input to a [BuildRequest] without this
 * module hard-coding a specific input schema.
 *
 * [ai.droidcommand.agent.PermissionCategory.TERMINAL] (CAP-010): no
 * dedicated "build" category exists in the roadmap prompt's 13-value
 * taxonomy, and a build ultimately reaches a real executor
 * (`core-build-local.LocalProcessBuildExecutor`) that spawns an external
 * compiler process — the same execution shape [ai.droidcommand.shell.ShellTool]
 * is categorized under, not a distinct kind of capability.
 */
class BuildTool(
    private val pipeline: BuildPipeline,
    private val requestFactory: (Map<String, String>) -> BuildRequest,
) : Tool {
    override val spec = ToolSpec(
        name = "build_project",
        description = "Builds a project through the DroidCommand build pipeline",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.TERMINAL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val request = try {
            requestFactory(input)
        } catch (e: Exception) {
            return ToolResult.Failure(e.message ?: "Invalid build tool input", e)
        }

        return when (val result = pipeline.execute(request)) {
            is BuildResult.Success ->
                ToolResult.Success("Build ${result.buildId} completed with ${result.artifacts.size} artifact(s)")

            is BuildResult.Failure ->
                ToolResult.Failure("Build ${result.buildId} failed at ${result.failedStage} (${result.error.code}): ${result.error.message}")
        }
    }
}
