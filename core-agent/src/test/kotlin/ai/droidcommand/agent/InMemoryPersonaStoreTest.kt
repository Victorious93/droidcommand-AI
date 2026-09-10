package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testPersona(id: String = "p1", enabled: Boolean = false) = Persona(
    id = id,
    name = "Alex",
    category = PersonaCategory.PERSONAL,
    sourceConversations = listOf("conv-1"),
    styleCharacteristics = StyleProfile(
        tone = "friendly",
        vocabulary = VocabProfile("simple"),
        sentenceStructure = StructureProfile("short", "casual"),
        formality = Formality.CASUAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(present = false),
        responseStructure = "direct",
    ),
    contextContribution = "Tone: friendly.",
    version = "1",
    enabled = enabled,
)

class InMemoryPersonaStoreTest {
    @Test
    fun `save then load returns the same persona`() {
        val store = InMemoryPersonaStore()
        val persona = testPersona()
        store.save(persona)

        assertEquals(persona, store.load("p1"))
    }

    @Test
    fun `load of an unknown id returns null`() {
        assertNull(InMemoryPersonaStore().load("does-not-exist"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona("zeta"))
        store.save(testPersona("alpha"))

        assertEquals(listOf("alpha", "zeta"), store.list())
    }

    @Test
    fun `delete removes a persona and reports whether it existed`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona("p1"))

        assertTrue(store.delete("p1"))
        assertFalse(store.delete("p1"))
        assertNull(store.load("p1"))
    }

    @Test
    fun `setEnabled true activates a disabled persona`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona(enabled = false))

        assertTrue(store.setEnabled("p1", true))
        assertTrue(store.load("p1")!!.enabled)
    }

    @Test
    fun `setEnabled false deactivates an enabled persona`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona(enabled = true))

        assertTrue(store.setEnabled("p1", false))
        assertFalse(store.load("p1")!!.enabled)
    }

    @Test
    fun `setEnabled on an unknown id returns false and saves nothing`() {
        val store = InMemoryPersonaStore()
        assertFalse(store.setEnabled("does-not-exist", true))
        assertNull(store.load("does-not-exist"))
    }
}
