package ai.droidforge.tools.android

import ai.droidforge.agent.AgentStateMachine
import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.ToolExecutor
import ai.droidforge.agent.ToolRegistry
import ai.droidforge.agent.ToolResult
import ai.droidforge.security.ApprovalPrompt
import ai.droidforge.security.SecurityPolicy
import ai.droidforge.security.SecurityPolicyEnforcer
import ai.droidforge.security.SecureToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves device-mutating tools (tap/swipe/type/launch/pressKey) go through
 * core-security's real approval machinery, exactly like core-build's
 * BuildTool does — nothing in this module reimplements approval logic,
 * and a denied action never reaches [DeviceController] at all.
 */
class DeviceToolSecureExecutorIntegrationTest {
    private fun secure(device: ScriptedDeviceController, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val registry = ToolRegistry().apply { register(TapTool(device)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(SecurityPolicy()), approvalPrompt)
    }

    @Test
    fun `tap is SENSITIVE and requires confirmation`() {
        val spec = TapTool(ScriptedDeviceController()).spec
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `a denied approval never reaches the device controller`() {
        val device = ScriptedDeviceController(tapResult = DeviceActionResult.Success("tapped"))
        val result = secure(device, ApprovalPrompt { false }).run("tap", mapOf("x" to "1", "y" to "2"))

        assertIs<ToolResult.Failure>(result)
        assertTrue((result as ToolResult.Failure).reason.contains("denied"))
        assertEquals(0, device.tapCalls.size)
    }

    @Test
    fun `a granted approval reaches the device controller for real`() {
        val device = ScriptedDeviceController(tapResult = DeviceActionResult.Success("tapped"))
        val result = secure(device, ApprovalPrompt { true }).run("tap", mapOf("x" to "1", "y" to "2"))

        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(1 to 2), device.tapCalls)
    }
}
