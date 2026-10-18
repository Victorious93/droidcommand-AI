package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunCommandResultBundleTest {
    @Test
    fun `command success with err 0 and exitCode 0`() {
        val result = RunCommandResultBundle.fromMap(
            mapOf(
                "stdout" to "22/tcp open ssh",
                "stderr" to "",
                "exitCode" to 0,
                "err" to 0,
            ),
        ).toExecutionResult(12)
        val success = assertIs<TermuxExecutionResult.Success>(result)
        assertEquals(0, success.exitCode)
        assertEquals("22/tcp open ssh", success.stdout)
        assertEquals(12, success.durationMillis)
    }

    @Test
    fun `command failure is exitCode nonzero with launch-ok err`() {
        val result = RunCommandResultBundle.fromMap(
            mapOf("stdout" to "", "stderr" to "nmap: failed", "exitCode" to 1, "err" to -1),
        ).toExecutionResult(3)
        val success = assertIs<TermuxExecutionResult.Success>(result)
        assertEquals(1, success.exitCode)
        assertEquals("nmap: failed", success.stderr)
    }

    @Test
    fun `launch failure is err neither 0 nor -1`() {
        val result = RunCommandResultBundle.fromMap(
            mapOf("err" to 2, "errmsg" to "allow-external-apps is not true"),
        ).toExecutionResult(1)
        val failure = assertIs<TermuxExecutionResult.Failure>(result)
        assertTrue(failure.reason.contains("launch/validation failure"))
        assertTrue(failure.reason.contains("allow-external-apps"))
        assertTrue(failure.reason.contains("err=2"))
    }

    @Test
    fun `missing exitCode is treated as the command did not run`() {
        val result = RunCommandResultBundle.fromMap(mapOf("err" to 0, "stdout" to "")).toExecutionResult(1)
        val failure = assertIs<TermuxExecutionResult.Failure>(result)
        assertTrue(failure.reason.contains("no exitCode"))
    }

    @Test
    fun `trimmed stdout is annotated from original_length`() {
        val result = RunCommandResultBundle.fromMap(
            mapOf(
                "stdout" to "tail",
                "stdout_original_length" to "1000",
                "exitCode" to 0,
                "err" to 0,
            ),
        ).toExecutionResult(1)
        val success = assertIs<TermuxExecutionResult.Success>(result)
        assertTrue(success.stdout.contains("original_length=1000"))
        assertTrue(success.stdout.contains("delivered=4"))
    }

    @Test
    fun `exitCode delivered as a string still parses`() {
        val result = RunCommandResultBundle.fromMap(
            mapOf("exitCode" to "0", "err" to "0", "stdout" to "ok"),
        ).toExecutionResult(1)
        assertEquals(0, assertIs<TermuxExecutionResult.Success>(result).exitCode)
    }
}
