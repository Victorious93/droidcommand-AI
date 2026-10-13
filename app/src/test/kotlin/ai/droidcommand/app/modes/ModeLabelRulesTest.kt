package ai.droidcommand.app.modes

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.pilot.parsePilotInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModeLabelRulesTest {
    @Test
    fun `defaults are mode 1 and mode 2 and the engines stay pilot and forge`() {
        assertEquals("Mode 1", ProductMode.MODE_ONE.defaultLabel)
        assertEquals("Mode 2", ProductMode.MODE_TWO.defaultLabel)
        assertEquals(AgentMode.PILOT, ProductMode.MODE_ONE.engineMode)
        assertEquals(AgentMode.FORGE, ProductMode.MODE_TWO.engineMode)
    }

    @Test
    fun `labels trim, cap, and treat blank as unset`() {
        assertNull(normalizeModeLabel("   "))
        assertNull(normalizeModeLabel(null))
        assertEquals("Night shift", normalizeModeLabel("  Night shift  "))
        assertEquals("a".repeat(24), normalizeModeLabel("a".repeat(40)))
    }

    @Test
    fun `pilot input is key value lines`() {
        assertEquals(mapOf("host" to "10.0.0.1", "note" to "a=b c"), parsePilotInput("host=10.0.0.1\n\n# skip\nnote=a=b c\n"))
        assertFailsWith<IllegalArgumentException> { parsePilotInput("no-equals") }
    }
}
