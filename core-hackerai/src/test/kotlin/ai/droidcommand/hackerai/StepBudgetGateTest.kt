package ai.droidcommand.hackerai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepBudgetGateTest {
    @Test
    fun `getSubagentExplorationStepLimit reserves two steps`() {
        assertEquals(3, getSubagentExplorationStepLimit(5))
        assertEquals(1, getSubagentExplorationStepLimit(3))
        assertEquals(0, getSubagentExplorationStepLimit(2))
        assertEquals(0, getSubagentExplorationStepLimit(1))
    }

    @Test
    fun `getSubagentExplorationStepLimit never goes negative`() {
        assertEquals(0, getSubagentExplorationStepLimit(0))
    }

    @Test
    fun `shouldStartResultRecovery false early in run`() {
        assertFalse(shouldStartResultRecovery(stepsTaken = 1, maxSteps = 50))
        assertFalse(shouldStartResultRecovery(stepsTaken = 47, maxSteps = 50))
    }

    @Test
    fun `shouldStartResultRecovery true at two steps before max`() {
        assertTrue(shouldStartResultRecovery(stepsTaken = 48, maxSteps = 50))
        assertTrue(shouldStartResultRecovery(stepsTaken = 50, maxSteps = 50))
    }
}
