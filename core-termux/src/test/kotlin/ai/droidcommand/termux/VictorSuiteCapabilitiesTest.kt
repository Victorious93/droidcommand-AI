package ai.droidcommand.termux

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedTermuxExecutor(private val result: TermuxExecutionResult) : TermuxExecutor {
    var lastCommand: TermuxCommand? = null
        private set

    override fun isAvailable(): Boolean = true

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        lastCommand = command
        return result
    }
}

class VictorSuiteCapabilitiesProbeTest {
    @Test fun `command is a single bash -lc invocation, never a persisted script path`() {
        val command = VictorSuiteCapabilitiesProbe.command()
        assertEquals("bash", command.executable)
        assertEquals("-lc", command.args.first())
        assertEquals(2, command.args.size)
    }

    @Test fun `parses a full set of key=value lines`() {
        val stdout = """
            termux_prefix=/data/data/com.termux/files/usr
            termux_home=/data/data/com.termux/files/home
            proot_distro_installed=true
            kali_installed=true
            kali_nmap=true
            kali_msfconsole=false
            ztcommand_port_listening=true
        """.trimIndent()
        val caps = VictorSuiteCapabilitiesProbe.parse(stdout)
        assertEquals("/data/data/com.termux/files/usr", caps.termuxPrefix)
        assertEquals("/data/data/com.termux/files/home", caps.termuxHome)
        assertTrue(caps.prootDistroInstalled)
        assertTrue(caps.kaliInstalled)
        assertTrue(caps.kaliHasNmap)
        assertFalse(caps.kaliHasMsfconsole)
        assertTrue(caps.ztCommandPortListening)
    }

    @Test fun `missing or garbled output defaults to false and null rather than throwing`() {
        val caps = VictorSuiteCapabilitiesProbe.parse("not even key=value pairs\n\njust noise")
        assertNull(caps.termuxPrefix)
        assertFalse(caps.prootDistroInstalled)
        assertFalse(caps.kaliInstalled)
        assertFalse(caps.ztCommandPortListening)
    }

    @Test fun `empty stdout parses to all-false, all-null`() {
        val caps = VictorSuiteCapabilitiesProbe.parse("")
        assertNull(caps.termuxPrefix)
        assertNull(caps.termuxHome)
        assertFalse(caps.kaliInstalled)
    }
}

class VictorSuiteCapabilitiesToolTest {
    @Test fun `spec declares SecurityLevel SENSITIVE and PermissionCategory TERMINAL, never ROOT`() {
        val spec = VictorSuiteCapabilitiesTool(NullTermuxExecutor()).spec
        assertFalse(spec.requiresRoot)
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertEquals(PermissionCategory.TERMINAL, spec.permissionCategory)
        assertTrue(spec.requiresConfirmation)
    }

    @Test fun `a zero exit code becomes Success describing the parsed capabilities`() {
        val executor = ScriptedTermuxExecutor(
            TermuxExecutionResult.Success(0, "kali_installed=true\nkali_nmap=true\nkali_msfconsole=false", "", 5),
        )
        val result = assertIs<ToolResult.Success>(VictorSuiteCapabilitiesTool(executor).execute(emptyMap()))
        assertTrue("kali_installed: true" in result.output)
        assertTrue("kali_nmap: true" in result.output)
        assertTrue("kali_msfconsole: false" in result.output)
    }

    @Test fun `a non-zero exit code becomes Failure`() {
        val executor = ScriptedTermuxExecutor(TermuxExecutionResult.Success(127, "", "bash: not found", 5))
        val result = assertIs<ToolResult.Failure>(VictorSuiteCapabilitiesTool(executor).execute(emptyMap()))
        assertTrue("not found" in result.reason)
    }

    @Test fun `surfaces an executor-level failure directly`() {
        val executor = ScriptedTermuxExecutor(TermuxExecutionResult.Failure("VictorSuite RUN_COMMAND is not available"))
        val result = assertIs<ToolResult.Failure>(VictorSuiteCapabilitiesTool(executor).execute(emptyMap()))
        assertEquals("VictorSuite RUN_COMMAND is not available", result.reason)
    }
}
