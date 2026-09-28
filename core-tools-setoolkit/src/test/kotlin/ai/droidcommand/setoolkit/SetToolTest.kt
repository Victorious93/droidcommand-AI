package ai.droidcommand.setoolkit

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedSetExecutor(private val result: SetExecutionResult) : SetExecutor {
    var lastCommand: SetCommand? = null
        private set

    override fun isAvailable(): Boolean = true

    override fun execute(command: SetCommand, isCancelled: () -> Boolean): SetExecutionResult {
        lastCommand = command
        return result
    }
}

class SetToolTest {
    @Test
    fun `spec declares SecurityLevel SENSITIVE and PermissionCategory NETWORK, always requiring confirmation`() {
        val spec = SetTool(NullSetExecutor()).spec
        assertEquals(false, spec.requiresRoot)
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertEquals(PermissionCategory.NETWORK, spec.permissionCategory)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `fails without executing when neither targetHost nor targetEmail is supplied`() {
        val executor = ScriptedSetExecutor(SetExecutionResult.Success(0, "", "", 0))
        val result = SetTool(executor).execute(mapOf("attackVector" to "WEBSITE_ATTACK"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }

    @Test
    fun `fails without executing when attackVector is unknown`() {
        val executor = ScriptedSetExecutor(SetExecutionResult.Success(0, "", "", 0))
        val result = SetTool(executor).execute(mapOf("attackVector" to "NOT_A_VECTOR", "targetHost" to "10.0.0.5"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }

    @Test
    fun `parses structured input into a SetCommand and forwards option prefixed keys`() {
        val executor = ScriptedSetExecutor(SetExecutionResult.Success(0, "done", "", 10))
        val result = SetTool(executor).execute(
            mapOf(
                "attackVector" to "spear_phishing",
                "targetEmail" to "target@authorized-lab.test",
                "payload" to "windows/meterpreter/reverse_tcp",
                "option.SMTP_SERVER" to "mail.authorized-lab.test",
            ),
        )
        assertIs<ToolResult.Success>(result)
        val command = executor.lastCommand!!
        assertEquals(SetAttackVector.SPEAR_PHISHING, command.attackVector)
        assertEquals("target@authorized-lab.test", command.target.emailAddress)
        assertEquals(mapOf("SMTP_SERVER" to "mail.authorized-lab.test"), command.options)
    }

    @Test
    fun `a non-zero exit code becomes ToolResult Failure`() {
        val executor = ScriptedSetExecutor(SetExecutionResult.Success(1, "", "setoolkit crashed", 5))
        val result = SetTool(executor).execute(mapOf("attackVector" to "WEBSITE_ATTACK", "targetHost" to "10.0.0.5"))
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("setoolkit crashed"))
    }

    @Test
    fun `an invalid target is rejected before the executor is ever invoked`() {
        val executor = ScriptedSetExecutor(SetExecutionResult.Success(0, "", "", 0))
        val result = SetTool(executor).execute(
            mapOf("attackVector" to "WEBSITE_ATTACK", "targetHost" to "10.0.0.5\nATTACK_VECTOR=MASS_MAILER"),
        )
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }
}
