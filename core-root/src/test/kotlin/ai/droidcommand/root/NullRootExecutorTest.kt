package ai.droidcommand.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullRootExecutorTest {
    private val executor = NullRootExecutor()

    @Test
    fun `reports root as unavailable, truthfully`() {
        assertFalse(executor.isRootAvailable())
    }

    @Test
    fun `execute fails explicitly rather than fabricating a successful elevated command`() {
        val result = assertIs<RootExecutionResult.Failure>(executor.execute(RootCommand("id")))
        assertTrue(result.reason.contains("no real rooted device"))
    }

    @Test
    fun `the failure message names the executable that was requested`() {
        val result = assertIs<RootExecutionResult.Failure>(executor.execute(RootCommand("rm")))
        assertEquals(true, result.reason.contains("rm"))
    }
}
