package ai.droidcommand.shizuku

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingShell : PrivilegedShell {
    var execCount = 0
        private set

    override fun isAvailable(): Boolean = true

    override fun exec(argv: List<String>, timeoutMs: Long): PrivilegedResult {
        execCount++
        return PrivilegedResult.Success(0, "should not run unless approved", "", false)
    }

    override fun readDeviceState(): PrivilegedResult {
        execCount++
        return PrivilegedResult.Success(0, "nope", "", false)
    }

    override fun pmGrant(packageName: String, permission: String): PrivilegedResult {
        execCount++
        return PrivilegedResult.Success(0, "nope", "", false)
    }
}

class ShizukuToolSecureExecutorIntegrationTest {
    private fun secure(shell: PrivilegedShell, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val registry = ToolRegistry().apply { ShizukuTool.all(shell).forEach { register(it) } }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val policy = SecurityPolicy(grantedCategories = setOf(PermissionCategory.DEVICE_CONTROL))
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    }

    @Test
    fun `a denied approval never reaches PrivilegedShell`() {
        val shell = CountingShell()
        val result = secure(shell, ApprovalPrompt { false })
            .run("run_shizuku_command", mapOf("executable" to "id"))
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("denied"))
        assertEquals(0, shell.execCount)
    }

    @Test
    fun `a granted approval runs exec device-state and pm-grant`() {
        val shell = CountingShell()
        val executor = secure(shell, ApprovalPrompt { true })
        assertIs<ToolResult.Success>(executor.run("run_shizuku_command", mapOf("executable" to "id")))
        assertIs<ToolResult.Success>(executor.run("shizuku_read_device_state", emptyMap()))
        assertIs<ToolResult.Success>(
            executor.run("shizuku_pm_grant", mapOf("package" to "ai.droidcommand.app", "permission" to "android.permission.INTERNET")),
        )
        assertEquals(3, shell.execCount)
    }
}
