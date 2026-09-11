package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testPersona(id: String, name: String = "Test Persona") = Persona(
    id = id,
    name = name,
    category = PersonaCategory.CUSTOM,
    sourceConversations = listOf("chat-1"),
    styleCharacteristics = StyleProfile(
        tone = "warm",
        vocabulary = VocabProfile("simple"),
        sentenceStructure = StructureProfile("short", "minimal"),
        formality = Formality.CASUAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(false),
        responseStructure = "bullet points",
    ),
    contextContribution = "Write warmly and concisely.",
    version = "1",
    enabled = false,
)

class InMemoryPersonaStoreTest {
    @Test
    fun `save then load returns an equal persona`() {
        val store = InMemoryPersonaStore()
        val persona = testPersona("p1")

        store.save(persona)

        assertEquals(persona, store.load("p1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(InMemoryPersonaStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona("b"))
        store.save(testPersona("a"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes a persona and reports whether it existed`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona("p1"))

        assertTrue(store.delete("p1"))
        assertNull(store.load("p1"))
        assertEquals(false, store.delete("p1"))
    }

    @Test
    fun `saving a persona with the same id overwrites the previous one`() {
        val store = InMemoryPersonaStore()
        store.save(testPersona("p1", name = "First"))
        store.save(testPersona("p1", name = "Second"))

        assertEquals("Second", store.load("p1")!!.name)
    }
}
