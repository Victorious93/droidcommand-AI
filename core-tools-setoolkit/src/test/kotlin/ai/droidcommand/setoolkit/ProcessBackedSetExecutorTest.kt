package ai.droidcommand.setoolkit

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import java.nio.file.Files
import java.nio.file.Path
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

class ProcessBackedSetExecutorTest {
    @Test
    fun `isAvailable is true once a shell executor is configured`() {
        val executor = ProcessBackedSetExecutor(ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertTrue(executor.isAvailable())
    }

    @Test
    fun `writes a real config file with the structured fields and dispatches via the default invocation builder`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(0, "attack launched", "", 500))
        val executor = ProcessBackedSetExecutor(shell)

        val command = SetCommand(
            attackVector = SetAttackVector.SPEAR_PHISHING,
            target = SetTarget(host = "10.0.0.5"),
            payload = "windows/meterpreter/reverse_tcp",
            options = mapOf("SMTP_SERVER" to "mail.authorized-lab.test"),
        )

        val result = assertIs<SetExecutionResult.Success>(executor.execute(command))
        assertEquals("attack launched", result.stdout)

        val dispatched = shell.lastCommand
        assertEquals("setoolkit", dispatched?.executable)
        assertEquals(1, dispatched?.args?.size)
        val configPath = Path.of(dispatched!!.args[0])
        val contents = Files.readString(configPath)
        assertTrue(contents.contains("ATTACK_VECTOR=SPEAR_PHISHING"))
        assertTrue(contents.contains("TARGET_HOST=10.0.0.5"))
        assertTrue(contents.contains("PAYLOAD=windows/meterpreter/reverse_tcp"))
        assertTrue(contents.contains("SMTP_SERVER=mail.authorized-lab.test"))
        Files.deleteIfExists(configPath)
    }

    @Test
    fun `honors a caller-supplied invocationBuilder instead of the unverified default`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        var capturedConfigPath: Path? = null
        val executor = ProcessBackedSetExecutor(
            shell,
            executablePath = "seautomate",
            invocationBuilder = { configFile ->
                capturedConfigPath = configFile
                listOf(configFile.toString(), "10.0.0.5")
            },
        )

        executor.execute(SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5")))

        assertEquals("seautomate", shell.lastCommand?.executable)
        assertEquals(listOf(capturedConfigPath.toString(), "10.0.0.5"), shell.lastCommand?.args)
    }

    @Test
    fun `surfaces a shell-level failure directly`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Failure("setoolkit not found"))
        val executor = ProcessBackedSetExecutor(shell)
        val result = assertIs<SetExecutionResult.Failure>(
            executor.execute(SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5"))),
        )
        assertEquals("setoolkit not found", result.reason)
    }
}
