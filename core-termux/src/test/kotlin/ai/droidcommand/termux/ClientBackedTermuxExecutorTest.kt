package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingRunCommandClient(
    private val bundle: RunCommandResultBundle,
) : RunCommandClient {
    var lastRequest: RunCommandRequest? = null
        private set

    override fun execute(
        request: RunCommandRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
    ): RunCommandResultBundle {
        lastRequest = request
        return bundle
    }
}

class ClientBackedTermuxExecutorTest {
    @Test
    fun `isAvailable follows the probe`() {
        assertFalse(ClientBackedTermuxExecutor({ _, _, _ -> RunCommandResultBundle() }, { false }).isAvailable())
        assertTrue(ClientBackedTermuxExecutor({ _, _, _ -> RunCommandResultBundle() }, { true }).isAvailable())
    }

    @Test
    fun `does not dispatch when unavailable`() {
        val client = RecordingRunCommandClient(RunCommandResultBundle(exitCode = 0, err = 0, stdout = "should not run"))
        val executor = ClientBackedTermuxExecutor(client, { false })
        val result = executor.execute(TermuxCommand(executable = "proot-distro", args = listOf("login", "kali", "--", "id")))
        assertIs<TermuxExecutionResult.Failure>(result)
        assertEquals(null, client.lastRequest)
    }

    @Test
    fun `maps a successful PendingIntent bundle onto TermuxExecutionResult`() {
        val client = RecordingRunCommandClient(
            RunCommandResultBundle(stdout = "uid=0", exitCode = 0, err = 0),
        )
        val executor = ClientBackedTermuxExecutor(client, { true }, commandLabel = "test")
        val result = executor.execute(
            TermuxCommand(executable = RunCommandContract.PROOT_DISTRO_PATH, args = listOf("login", "kali", "--", "id")),
        )
        val success = assertIs<TermuxExecutionResult.Success>(result)
        assertEquals("uid=0", success.stdout)
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, client.lastRequest?.path)
        assertEquals(true, client.lastRequest?.background)
        assertEquals("test", client.lastRequest?.commandLabel)
    }

    @Test
    fun `launch failure is not a successful command run`() {
        val client = RecordingRunCommandClient(
            RunCommandResultBundle(err = 2, errmsg = "allow-external-apps is not true"),
        )
        val executor = ClientBackedTermuxExecutor(client, { true })
        val result = executor.execute(
            TermuxCommand(executable = RunCommandContract.PROOT_DISTRO_PATH, args = listOf("login", "kali", "--", "id")),
        )
        val failure = assertIs<TermuxExecutionResult.Failure>(result)
        assertTrue(failure.reason.contains("launch/validation failure"))
        assertFalse(failure.reason.contains("exitCode"))
    }
}
