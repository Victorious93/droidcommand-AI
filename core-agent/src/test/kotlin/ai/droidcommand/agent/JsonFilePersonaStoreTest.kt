package ai.droidcommand.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFilePersonaStoreTest {
    private fun newStore() = JsonFilePersonaStore(Files.createTempDirectory("droidcommand-persona-store-test"))

    private fun persona(id: String, enabled: Boolean = false) = Persona(
        id = id,
        name = "Alex",
        category = PersonaCategory.CODING,
        sourceConversations = listOf("conv-1", "conv-2"),
        styleCharacteristics = StyleProfile(
            tone = "terse and technical",
            vocabulary = VocabProfile("advanced", notableVocabulary = listOf("idempotent"), jargonDomains = listOf("software engineering")),
            sentenceStructure = StructureProfile("short", "clipped", punctuationHabits = listOf("frequent em-dashes")),
            formality = Formality.NEUTRAL,
            verbosity = Verbosity.TERSE,
            humor = HumorProfile(present = true, style = "dry", examples = listOf("deadpan asides")),
            responseStructure = "code first, explanation after",
            commonExpressions = listOf("to be fair"),
        ),
        contextContribution = "Tone: terse and technical.",
        version = "1",
        enabled = enabled,
    )

    @Test
    fun `save then load returns an equal persona, including nested style fields`() {
        val store = newStore()
        val p = persona("alex-1")

        store.save(p)

        assertEquals(p, store.load("alex-1"))
    }

    @Test
    fun `a persona survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-persona-store-test")
        val p = persona("alex-1")
        JsonFilePersonaStore(dir).save(p)

        assertEquals(p, JsonFilePersonaStore(dir).load("alex-1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = newStore()
        store.save(persona("zeta"))
        store.save(persona("alpha"))

        assertEquals(listOf("alpha", "zeta"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save(persona("alex-1"))

        assertTrue(store.delete("alex-1"))
        assertNull(store.load("alex-1"))
        assertEquals(false, store.delete("alex-1"))
    }

    @Test
    fun `setEnabled survives a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-persona-store-test")
        JsonFilePersonaStore(dir).save(persona("alex-1", enabled = false))

        val toggled = JsonFilePersonaStore(dir)
        assertTrue(toggled.setEnabled("alex-1", true))

        val reopened = JsonFilePersonaStore(dir)
        assertTrue(reopened.load("alex-1")!!.enabled)
    }

    @Test
    fun `setEnabled on an unknown id returns false`() {
        assertEquals(false, newStore().setEnabled("does-not-exist", true))
    }

    @Test
    fun `an id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidPersonaId> { store.save(persona("../escape")) }
        assertFailsWith<InvalidPersonaId> { store.load("../escape") }
        assertFailsWith<InvalidPersonaId> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected`() {
        assertFailsWith<InvalidPersonaId> { newStore().save(persona("/etc/passwd")) }
    }
}
