package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModeLabelsTest {
    @Test
    fun `defaults are Mode 1 and Mode 2`() {
        assertEquals("Mode 1", ModeLabels.defaultFor(AgentMode.PILOT))
        assertEquals("Mode 2", ModeLabels.defaultFor(AgentMode.FORGE))
        assertEquals("Mode 1", ModeLabels.display(null, AgentMode.PILOT))
        assertEquals("Mode 2", ModeLabels.display("   ", AgentMode.FORGE))
    }

    @Test
    fun `blank and control-only input means use the default`() {
        assertNull(ModeLabels.normalize("  \n\t "))
        assertNull(ModeLabels.normalize("\u0001\u0002"))
    }

    @Test
    fun `whitespace collapses and control characters are removed`() {
        assertEquals("Night shift", ModeLabels.normalize("  Night \n shift  "))
        assertEquals("ab", ModeLabels.normalize("a\u0001b"))
    }

    @Test
    fun `labels are capped`() {
        val long = "x".repeat(ModeLabels.MAX_LENGTH + 10)
        assertEquals(ModeLabels.MAX_LENGTH, ModeLabels.normalize(long)!!.length)
    }
}
