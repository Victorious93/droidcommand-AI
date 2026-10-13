package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModeLabelsTest {
    @Test
    fun `defaults name Pilot Mode 1 and Forge Mode 2`() {
        val labels = ModeLabels()
        assertEquals("Mode 1", labels.labelFor(AgentMode.PILOT))
        assertEquals("Mode 2", labels.labelFor(AgentMode.FORGE))
        assertEquals("Pilot", labels.engineName(AgentMode.PILOT))
        assertEquals("Forge", labels.engineName(AgentMode.FORGE))
    }

    @Test
    fun `rename changes only the chosen label and trims it`() {
        val renamed = ModeLabels().rename(AgentMode.PILOT, "  Scout  ").rename(AgentMode.FORGE, "Builder")
        assertEquals("Scout", renamed.labelFor(AgentMode.PILOT))
        assertEquals("Builder", renamed.labelFor(AgentMode.FORGE))
        assertEquals("Pilot", renamed.engineName(AgentMode.PILOT))
        assertEquals("Forge", renamed.engineName(AgentMode.FORGE))
    }

    @Test
    fun `blank and duplicate labels are rejected`() {
        assertFailsWith<IllegalArgumentException> { ModeLabels(mode1 = "   ") }
        assertFailsWith<IllegalArgumentException> { ModeLabels(mode1 = "Same", mode2 = " Same ") }
        assertFailsWith<IllegalArgumentException> { ModeLabels().rename(AgentMode.FORGE, "Mode 1") }
    }
}
