package ai.droidforge.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedRootExecutor(
    private val rootAvailable: Boolean = true,
    private val result: RootExecutionResult = RootExecutionResult.Failure("not scripted"),
) : RootExecutor {
    var executeCalls = mutableListOf<RootCommand>()
        private set

    override fun isRootAvailable(): Boolean = rootAvailable

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        executeCalls += command
        return result
    }
}

class PolicyEnforcingRootExecutorTest {
    @Test
    fun `rejects a command outside the allow-list without reaching the delegate`() {
        val delegate = ScriptedRootExecutor()
        val executor = PolicyEnforcingRootExecutor(RootSecurityPolicy(allowedExecutables = setOf("id")), delegate)

        val result = assertIs<RootExecutionResult.Failure>(executor.execute(RootCommand("rm")))

        assertTrue(result.reason.contains("not in the allowed"))
        assertEquals(0, delegate.executeCalls.size)
    }

    @Test
    fun `an empty allow-list rejects every command, fail-closed by default`() {
        val delegate = ScriptedRootExecutor()
        val executor = PolicyEnforcingRootExecutor(RootSecurityPolicy(), delegate)

        assertIs<RootExecutionResult.Failure>(executor.execute(RootCommand("id")))
        assertEquals(0, delegate.executeCalls.size)
    }

    @Test
    fun `an allow-listed command reaches the delegate`() {
        val delegate = ScriptedRootExecutor(result = RootExecutionResult.Success(0, "uid=0(root)", "", 5))
        val executor = PolicyEnforcingRootExecutor(RootSecurityPolicy(allowedExecutables = setOf("id")), delegate)

        val result = assertIs<RootExecutionResult.Success>(executor.execute(RootCommand("id")))

        assertEquals("uid=0(root)", result.stdout)
        assertEquals(1, delegate.executeCalls.size)
    }

    @Test
    fun `isRootAvailable passes through to the delegate`() {
        val unavailable = PolicyEnforcingRootExecutor(RootSecurityPolicy(), ScriptedRootExecutor(rootAvailable = false))
        val available = PolicyEnforcingRootExecutor(RootSecurityPolicy(), ScriptedRootExecutor(rootAvailable = true))

        assertFalse(unavailable.isRootAvailable())
        assertTrue(available.isRootAvailable())
    }
}
