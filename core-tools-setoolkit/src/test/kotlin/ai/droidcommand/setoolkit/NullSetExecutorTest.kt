package ai.droidcommand.setoolkit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullSetExecutorTest {
    @Test
    fun `isAvailable is truthfully false`() {
        assertFalse(NullSetExecutor().isAvailable())
    }

    @Test
    fun `execute fails explicitly rather than fabricating a run`() {
        val command = SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5"))
        val result = assertIs<SetExecutionResult.Failure>(NullSetExecutor().execute(command))
        assertTrue(result.reason.contains("no real Social-Engineer Toolkit backend"))
    }
}
