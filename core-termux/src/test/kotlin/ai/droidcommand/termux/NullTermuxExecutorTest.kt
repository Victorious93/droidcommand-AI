package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullTermuxExecutorTest {
    private val executor = NullTermuxExecutor()

    @Test
    fun `reports Termux as unavailable, truthfully`() {
        assertFalse(executor.isAvailable())
    }

    @Test
    fun `execute fails explicitly rather than fabricating a successful command`() {
        val result = assertIs<TermuxExecutionResult.Failure>(executor.execute(TermuxCommand("echo")))
        assertTrue(result.reason.contains("no real Termux backend"))
    }

    @Test
    fun `the failure message names the executable that was requested`() {
        val result = assertIs<TermuxExecutionResult.Failure>(executor.execute(TermuxCommand("ls")))
        assertEquals(true, result.reason.contains("ls"))
    }
}
