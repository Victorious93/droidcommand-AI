package ai.droidcommand.agent

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testPersona(id: String, name: String = "Test Persona", enabled: Boolean = false) = Persona(
    id = id,
    name = name,
    category = PersonaCategory.CUSTOM,
    sourceConversations = listOf("chat-1"),
    styleCharacteristics = StyleProfile(
        tone = "warm",
        vocabulary = VocabProfile("simple", notableWords = listOf("y'know")),
        sentenceStructure = StructureProfile("short", "minimal"),
        formality = Formality.CASUAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(true, style = "dry"),
        responseStructure = "bullet points",
        commonExpressions = listOf("no worries"),
    ),
    contextContribution = "Write warmly and concisely.",
    version = "1",
    enabled = enabled,
)

class JsonFilePersonaStoreTest {
    private fun newStore() = JsonFilePersonaStore(Files.createTempDirectory("droidcommand-persona-store-test"))

    @Test
    fun `save then load returns an equal persona`() {
        val store = newStore()
        val persona = testPersona("p1")

        store.save(persona)

        assertEquals(persona, store.load("p1"))
    }

    @Test
    fun `a persona survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-persona-store-test")
        val persona = testPersona("p1")
        JsonFilePersonaStore(dir).save(persona)

        assertEquals(persona, JsonFilePersonaStore(dir).load("p1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = newStore()
        store.save(testPersona("b"))
        store.save(testPersona("a"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save(testPersona("p1"))

        assertTrue(store.delete("p1"))
        assertNull(store.load("p1"))
        assertEquals(false, store.delete("p1"))
    }

    @Test
    fun `saving a persona with the same id overwrites the previous one`() {
        val store = newStore()
        store.save(testPersona("p1", name = "First"))
        store.save(testPersona("p1", name = "Second"))

        assertEquals("Second", store.load("p1")!!.name)
    }

    @Test
    fun `every StyleProfile field round-trips, including nested profiles and lists`() {
        val store = newStore()
        val persona = testPersona("p1", enabled = true)

        store.save(persona)
        val loaded = store.load("p1")!!

        assertEquals(persona.styleCharacteristics, loaded.styleCharacteristics)
        assertEquals(true, loaded.enabled)
    }

    @Test
    fun `an id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidPersonaId> { store.save(testPersona("../escape")) }
        assertFailsWith<InvalidPersonaId> { store.load("../escape") }
        assertFailsWith<InvalidPersonaId> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidPersonaId> { store.save(testPersona("/etc/passwd")) }
    }

    @Test
    fun `a file with an unrecognized enum value throws IOException naming the persona id`() {
        val dir = Files.createTempDirectory("droidcommand-persona-store-test")
        val store = JsonFilePersonaStore(dir)
        store.save(testPersona("p1"))
        val file = dir.resolve("p1.persona.json")
        Files.writeString(file, Files.readString(file).replace("\"CUSTOM\"", "\"NOT_A_CATEGORY\""))

        val e = assertFailsWith<IOException> { store.load("p1") }
        assertTrue(e.message!!.contains("p1"))
    }
}
