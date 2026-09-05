package ai.droidcommand.apklifecycle

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.security.SecureToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Proves deployment goes through core-security's real approval machinery, exactly like core-build's BuildTool. */
class ApkLifecycleToolSecureExecutorIntegrationTest {
    private fun secure(executor: ScriptedApkLifecycleExecutor, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val tool = ApkLifecycleTool(ApkLifecyclePipeline(executor)) { buildSuccess() }
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(SecurityPolicy()), approvalPrompt)
    }

    @Test
    fun `deploy_and_launch is SENSITIVE and requires confirmation`() {
        val tool = ApkLifecycleTool(ApkLifecyclePipeline(ScriptedApkLifecycleExecutor())) { buildSuccess() }
        assertEquals(SecurityLevel.SENSITIVE, tool.spec.securityLevel)
        assertTrue(tool.spec.requiresConfirmation)
    }

    @Test
    fun `a denied approval never reaches the lifecycle executor`() {
        val executor = ScriptedApkLifecycleExecutor(installResult = InstallResult.Success("com.example.app"))
        val result = secure(executor, ApprovalPrompt { false }).run("deploy_and_launch", mapOf("packageName" to "com.example.app"))

        assertIs<ToolResult.Failure>(result)
        assertTrue((result as ToolResult.Failure).reason.contains("denied"))
        assertEquals(0, executor.installCalls.size)
    }

    @Test
    fun `a granted approval reaches the lifecycle executor for real`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
        )
        val result = secure(executor, ApprovalPrompt { true }).run("deploy_and_launch", mapOf("packageName" to "com.example.app"))

        assertIs<ToolResult.Success>(result)
        assertEquals(1, executor.installCalls.size)
    }
}
