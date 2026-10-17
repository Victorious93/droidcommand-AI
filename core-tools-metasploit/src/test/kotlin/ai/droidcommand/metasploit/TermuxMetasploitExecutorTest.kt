package ai.droidcommand.metasploit

import ai.droidcommand.termux.ProotDistroKaliEnvelope
import ai.droidcommand.termux.RunCommandContract
import ai.droidcommand.termux.TermuxCommand
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingTermuxExecutor(
    private val available: Boolean,
    private val result: TermuxExecutionResult,
) : TermuxExecutor {
    var lastCommand: TermuxCommand? = null
        private set

    override fun isAvailable(): Boolean = available

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        lastCommand = command
        return result
    }
}

class TermuxMetasploitExecutorTest {
    private val command = MetasploitCommand(
        moduleType = MetasploitModuleType.AUXILIARY,
        modulePath = "auxiliary/scanner/portscan/tcp",
        target = MetasploitTarget("10.0.0.5"),
        options = mapOf("PORTS" to "22,80"),
    )

    @Test
    fun `isAvailable follows TermuxExecutor`() {
        assertFalse(
            TermuxMetasploitExecutor(RecordingTermuxExecutor(false, TermuxExecutionResult.Failure("no"))).isAvailable(),
        )
        assertTrue(
            TermuxMetasploitExecutor(
                RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0)),
            ).isAvailable(),
        )
    }

    @Test
    fun `does not dispatch when Termux is unavailable`() {
        val termux = RecordingTermuxExecutor(false, TermuxExecutionResult.Success(0, "should not run", "", 0))
        val result = TermuxMetasploitExecutor(termux).execute(command)
        assertIs<MetasploitExecutionResult.Failure>(result)
        assertEquals(null, termux.lastCommand)
    }

    @Test
    fun `dispatches msfconsole -q -r through proot-distro login kali`() {
        val termux = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "Auxiliary module complete", "", 40))
        val result = TermuxMetasploitExecutor(termux, ProotDistroKaliEnvelope()).execute(command)
        val success = assertIs<MetasploitExecutionResult.Success>(result)
        assertEquals("Auxiliary module complete", success.stdout)
        val dispatched = termux.lastCommand!!
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, dispatched.executable)
        assertEquals("login", dispatched.args[0])
        assertEquals("kali", dispatched.args[1])
        assertEquals("--", dispatched.args[2])
        assertEquals("bash", dispatched.args[3])
        assertEquals("-lc", dispatched.args[4])
        val pipeline = dispatched.args[5]
        assertTrue(pipeline.contains("msfconsole -q -r"))
        assertTrue(pipeline.contains("use auxiliary/scanner/portscan/tcp"))
        assertTrue(pipeline.contains("set RHOSTS 10.0.0.5"))
        assertTrue(pipeline.contains("set PORTS 22,80"))
    }
}
