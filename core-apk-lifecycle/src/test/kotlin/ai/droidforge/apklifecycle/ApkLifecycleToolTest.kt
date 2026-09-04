package ai.droidforge.apklifecycle

import ai.droidforge.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApkLifecycleToolTest {
    @Test
    fun `fails without running the pipeline when packageName is missing`() {
        var pipelineRan = false
        val executor = ScriptedApkLifecycleExecutor()
        val pipeline = ApkLifecyclePipeline(executor)
        val tool = ApkLifecycleTool(pipeline) { pipelineRan = true; buildSuccess() }

        val result = tool.execute(emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(false, pipelineRan)
    }

    @Test
    fun `a successful deployment reports the launch message`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched com.example.app"),
            logsResult = LogsResult.Success(emptyList()),
        )
        val tool = ApkLifecycleTool(ApkLifecyclePipeline(executor)) { buildSuccess() }

        val result = assertIs<ToolResult.Success>(tool.execute(mapOf("packageName" to "com.example.app")))
        assertEquals(true, result.output.contains("launched com.example.app"))
    }

    @Test
    fun `a pipeline failure is reported with its stage and error code`() {
        val tool = ApkLifecycleTool(ApkLifecyclePipeline(ScriptedApkLifecycleExecutor())) { buildFailure() }

        val result = assertIs<ToolResult.Failure>(tool.execute(mapOf("packageName" to "com.example.app")))
        assertEquals(true, result.reason.contains("BUILD_FAILED"))
    }

    @Test
    fun `runTests input is parsed and threaded through to the pipeline`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
            testRunResult = TestRunResult.Success(emptyList(), 0),
        )
        val tool = ApkLifecycleTool(ApkLifecyclePipeline(executor)) { buildSuccess() }

        tool.execute(mapOf("packageName" to "com.example.app", "runTests" to "true"))

        assertEquals(1, executor.runTestsCalls)
    }
}
