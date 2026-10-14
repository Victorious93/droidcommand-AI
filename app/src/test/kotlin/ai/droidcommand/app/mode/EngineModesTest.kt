package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineModesTest {
    private class MemoryStore(
        private var mode: AgentMode = AgentMode.PILOT,
        private val labels: MutableMap<AgentMode, String?> = mutableMapOf(),
    ) : ModePreferenceStore {
        override fun readMode(): AgentMode = mode

        override fun writeMode(mode: AgentMode) {
            this.mode = mode
        }

        override fun readLabel(mode: AgentMode): String? = labels[mode]

        override fun writeLabel(mode: AgentMode, stored: String?) {
            labels[mode] = stored
        }
    }

    @Test
    fun `labels and the selected mode survive a new instance`() {
        val store = MemoryStore()
        val first = EngineModes(store)
        first.rename(AgentMode.FORGE, "  Builder  ")
        assertTrue(first.select(AgentMode.FORGE))

        val second = EngineModes(store)
        assertEquals(AgentMode.FORGE, second.state.value.selected)
        assertEquals("Builder", second.state.value.forgeLabel)
        assertEquals("Mode 1", second.state.value.pilotLabel)
    }

    @Test
    fun `blank rename restores the default and clears the stored label`() {
        val store = MemoryStore()
        val modes = EngineModes(store)
        modes.rename(AgentMode.PILOT, "Alpha")
        modes.rename(AgentMode.PILOT, "   ")
        assertEquals("Mode 1", modes.state.value.pilotLabel)
        assertNull(store.readLabel(AgentMode.PILOT))
    }

    @Test
    fun `reset restores both defaults`() {
        val modes = EngineModes(MemoryStore())
        modes.rename(AgentMode.PILOT, "Alpha")
        modes.rename(AgentMode.FORGE, "Beta")
        modes.resetLabels()
        assertEquals("Mode 1", modes.state.value.pilotLabel)
        assertEquals("Mode 2", modes.state.value.forgeLabel)
    }

    @Test
    fun `a running task blocks switching to the other mode`() {
        val modes = EngineModes(MemoryStore())
        assertTrue(modes.tryBegin(AgentMode.PILOT))
        assertFalse(modes.select(AgentMode.FORGE))
        assertEquals(AgentMode.PILOT, modes.state.value.selected)
        assertEquals("Finish the running task before switching modes.", modes.state.value.switchError)
        // Staying on the current mode is still allowed, so its screen can be reopened.
        assertTrue(modes.select(AgentMode.PILOT))
        modes.endTask()
        assertNull(modes.state.value.switchError)
        assertTrue(modes.select(AgentMode.FORGE))
    }

    @Test
    fun `a task can only start in the selected mode`() {
        val modes = EngineModes(MemoryStore())
        assertFalse(modes.tryBegin(AgentMode.FORGE))
        assertFalse(modes.state.value.busy)
        assertTrue(modes.state.value.switchError!!.contains("Mode 2"))
        assertTrue(modes.select(AgentMode.FORGE))
        assertTrue(modes.tryBegin(AgentMode.FORGE))
        assertFalse(modes.tryBegin(AgentMode.FORGE))
        assertTrue(modes.state.value.busy)
    }
}
