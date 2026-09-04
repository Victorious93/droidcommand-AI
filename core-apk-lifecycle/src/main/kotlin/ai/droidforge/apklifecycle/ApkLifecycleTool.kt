package ai.droidforge.apklifecycle

import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.Tool
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec
import ai.droidforge.build.BuildResult

/**
 * Exposes [ApkLifecyclePipeline] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `core-build.BuildTool` and `core-tools-android`'s device
 * tools — installing and launching an app is `SENSITIVE`, not `NORMAL`.
 * [buildResultProvider] supplies the completed build to deploy, keeping
 * this tool decoupled from how or when that build actually ran.
 */
class ApkLifecycleTool(
    private val pipeline: ApkLifecyclePipeline,
    private val buildResultProvider: () -> BuildResult,
) : Tool {
    override val spec = ToolSpec(name = "deploy_and_launch", description = "Installs and launches a built app on a device", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val packageName = input["packageName"] ?: return ToolResult.Failure("Missing required input 'packageName'")
        val runTests = input["runTests"]?.equals("true", ignoreCase = true) ?: false

        return when (val result = pipeline.run(buildResultProvider(), packageName, runTests)) {
            is ApkLifecycleResult.Success -> ToolResult.Success(
                "Deployed $packageName: ${result.launchResult.message}" +
                    (result.testResults?.let { " (${it.results.size} test(s) ran)" } ?: ""),
            )
            is ApkLifecycleResult.Failure -> ToolResult.Failure("Deployment failed at ${result.stage} (${result.error.code}): ${result.error.message}")
        }
    }
}
