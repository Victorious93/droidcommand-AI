package ai.droidcommand.shell

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ShellExecutor.executeBinary]'s default implementation, exercised against a minimal implementation
 * that only overrides [ShellExecutor.execute] — the shape every [ShellExecutor] written before this
 * method existed still compiles as (a real regression this default guards against: adding an abstract
 * method here would have broken every existing implementer instead).
 */
class ShellExecutorTest {
    private class TextOnlyShellExecutor : ShellExecutor {
        override fun execute(command: ShellCommand, isCancelled: () -> Boolean) =
            ShellExecutionResult.Success(exitCode = 0, stdout = "", stderr = "", durationMillis = 0)
    }

    @Test
    fun `executeBinary's default fails honestly for an executor that has not added real binary support`() {
        val result = assertIs<ShellBinaryExecutionResult.Failure>(
            TextOnlyShellExecutor().executeBinary(ShellCommand(executable = "x")),
        )
        assertTrue(result.reason.contains("not supported"))
    }
}
