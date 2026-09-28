package ai.droidcommand.metasploit

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedMetasploitExecutor(private val result: MetasploitExecutionResult) : MetasploitExecutor {
    var lastCommand: MetasploitCommand? = null
        private set

    override fun isAvailable(): Boolean = true

    override fun execute(command: MetasploitCommand, isCancelled: () -> Boolean): MetasploitExecutionResult {
        lastCommand = command
        return result
    }
}

class MetasploitToolTest {
    @Test
    fun `spec declares SecurityLevel SENSITIVE and PermissionCategory NETWORK, always requiring confirmation`() {
        val spec = MetasploitTool(NullMetasploitExecutor()).spec
        assertEquals(false, spec.requiresRoot)
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertEquals(PermissionCategory.NETWORK, spec.permissionCategory)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `fails without executing when targetHost is missing`() {
        val executor = ScriptedMetasploitExecutor(MetasploitExecutionResult.Success(0, "", "", 0))
        val result = MetasploitTool(executor).execute(
            mapOf("moduleType" to "AUXILIARY", "modulePath" to "auxiliary/scanner/portscan/tcp"),
        )
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }

    @Test
    fun `fails without executing when moduleType is unknown`() {
        val executor = ScriptedMetasploitExecutor(MetasploitExecutionResult.Success(0, "", "", 0))
        val result = MetasploitTool(executor).execute(
            mapOf("moduleType" to "NOT_A_TYPE", "modulePath" to "exploit/x", "targetHost" to "10.0.0.5"),
        )
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }

    @Test
    fun `parses structured input into a MetasploitCommand and forwards option prefixed keys`() {
        val executor = ScriptedMetasploitExecutor(MetasploitExecutionResult.Success(0, "done", "", 10))
        val result = MetasploitTool(executor).execute(
            mapOf(
                "moduleType" to "exploit",
                "modulePath" to "exploit/windows/smb/ms17_010_eternalblue",
                "targetHost" to "10.0.0.5",
                "targetPort" to "445",
                "payload" to "payload/windows/x64/meterpreter/reverse_tcp",
                "option.LHOST" to "10.0.0.1",
                "option.LPORT" to "4444",
            ),
        )
        assertIs<ToolResult.Success>(result)
        val command = executor.lastCommand!!
        assertEquals(MetasploitModuleType.EXPLOIT, command.moduleType)
        assertEquals("10.0.0.5", command.target.host)
        assertEquals(445, command.target.port)
        assertEquals(mapOf("LHOST" to "10.0.0.1", "LPORT" to "4444"), command.options)
    }

    @Test
    fun `a non-zero exit code becomes ToolResult Failure`() {
        val executor = ScriptedMetasploitExecutor(MetasploitExecutionResult.Success(1, "", "module not found", 5))
        val result = MetasploitTool(executor).execute(
            mapOf("moduleType" to "AUXILIARY", "modulePath" to "auxiliary/scanner/portscan/tcp", "targetHost" to "10.0.0.5"),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("module not found"))
    }

    @Test
    fun `an invalid modulePath is rejected before the executor is ever invoked`() {
        val executor = ScriptedMetasploitExecutor(MetasploitExecutionResult.Success(0, "", "", 0))
        val result = MetasploitTool(executor).execute(
            mapOf("moduleType" to "AUXILIARY", "modulePath" to "auxiliary/scan; rm -rf /", "targetHost" to "10.0.0.5"),
        )
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, executor.lastCommand)
    }
}
