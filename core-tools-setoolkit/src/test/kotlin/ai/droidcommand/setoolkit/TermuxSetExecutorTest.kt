package ai.droidcommand.setoolkit

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

class TermuxSetExecutorTest {
    private val command = SetCommand(
        attackVector = SetAttackVector.WEBSITE_ATTACK,
        target = SetTarget(host = "10.0.0.5"),
        menuAnswers = listOf("2", "10.0.0.5", "y"),
    )

    @Test
    fun `isAvailable follows TermuxExecutor`() {
        assertFalse(TermuxSetExecutor(RecordingTermuxExecutor(false, TermuxExecutionResult.Failure("no"))).isAvailable())
        assertTrue(
            TermuxSetExecutor(RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0))).isAvailable(),
        )
    }

    @Test
    fun `does not dispatch when Termux is unavailable`() {
        val termux = RecordingTermuxExecutor(false, TermuxExecutionResult.Success(0, "should not run", "", 0))
        val result = TermuxSetExecutor(termux).execute(command)
        assertIs<SetExecutionResult.Failure>(result)
        assertEquals(null, termux.lastCommand)
    }

    @Test
    fun `stdinSequence prepends the menu-path digits by default`() {
        assertEquals(listOf("1", "2", "2", "10.0.0.5", "y"), TermuxSetExecutor.stdinSequence(command))
    }

    @Test
    fun `useMenuPath false sends only the caller's own menuAnswers`() {
        val noNav = command.copy(useMenuPath = false)
        assertEquals(listOf("2", "10.0.0.5", "y"), TermuxSetExecutor.stdinSequence(noNav))
    }

    @Test
    fun `an empty menuAnswers still gets the navigation digits, not an empty sequence`() {
        val navOnly = SetCommand(attackVector = SetAttackVector.SPEAR_PHISHING, target = SetTarget(host = "10.0.0.5"))
        assertEquals(listOf("1", "1"), TermuxSetExecutor.stdinSequence(navOnly))
    }

    @Test
    fun `SMS_SPOOFING fails cleanly with a specific reason instead of crashing or sending the wrong digit`() {
        val termux = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "should not run", "", 0))
        val smsCommand = SetCommand(attackVector = SetAttackVector.SMS_SPOOFING, target = SetTarget(host = "+15555550123"))
        val result = TermuxSetExecutor(termux).execute(smsCommand)
        val failure = assertIs<SetExecutionResult.Failure>(result)
        assertTrue(failure.reason.contains("removed"))
        assertEquals(null, termux.lastCommand) // never dispatched - no wrong digit was ever sent
    }

    @Test
    fun `dispatches setoolkit stdin through proot-distro login kali with the exact previewed sequence`() {
        val termux = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "done", "", 40))
        val result = TermuxSetExecutor(termux, ProotDistroKaliEnvelope()).execute(command)
        val success = assertIs<SetExecutionResult.Success>(result)
        assertEquals("done", success.stdout)
        val dispatched = termux.lastCommand!!
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, dispatched.executable)
        assertEquals(listOf("login", "kali", "--", "bash", "-lc"), dispatched.args.dropLast(1))
        val pipeline = dispatched.args.last()
        assertTrue(pipeline.contains("setoolkit < "))
        for (line in TermuxSetExecutor.stdinSequence(command)) assertTrue(pipeline.contains(line), "pipeline missing '$line'")
    }
}
