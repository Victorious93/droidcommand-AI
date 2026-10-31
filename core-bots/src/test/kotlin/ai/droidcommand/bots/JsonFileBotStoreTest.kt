package ai.droidcommand.bots

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testBot(id: String, name: String = "Test Bot", enabled: Boolean = true) = BotRecipe(
    id = id,
    name = name,
    personaId = "concise",
    instructions = "Only answer research questions.",
    providerType = "anthropic",
    allowedTools = setOf("web_search_tool"),
    memoryFolder = "Bots/$id",
    enabled = enabled,
)

class JsonFileBotStoreTest {
    private fun newStore() = JsonFileBotStore(Files.createTempDirectory("droidcommand-bot-store-test"))

    @Test
    fun `save then load returns an equal recipe`() {
        val store = newStore()
        val recipe = testBot("b1")

        store.save(recipe)

        assertEquals(recipe, store.load("b1"))
    }

    @Test
    fun `a recipe survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-bot-store-test")
        val recipe = testBot("b1")
        JsonFileBotStore(dir).save(recipe)

        assertEquals(recipe, JsonFileBotStore(dir).load("b1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = newStore()
        store.save(testBot("b"))
        store.save(testBot("a"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save(testBot("b1"))

        assertTrue(store.delete("b1"))
        assertNull(store.load("b1"))
        assertEquals(false, store.delete("b1"))
    }

    @Test
    fun `saving a recipe with the same id overwrites the previous one`() {
        val store = newStore()
        store.save(testBot("b1", name = "First"))
        store.save(testBot("b1", name = "Second"))

        assertEquals("Second", store.load("b1")!!.name)
    }

    @Test
    fun `empty allowed tools round-trips as chat only`() {
        val store = newStore()
        val recipe = testBot("b1").copy(allowedTools = emptySet())

        store.save(recipe)

        assertTrue(store.load("b1")!!.allowedTools.isEmpty())
    }

    @Test
    fun `an id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidBotId> { store.save(testBot("../escape")) }
        assertFailsWith<InvalidBotId> { store.load("../escape") }
        assertFailsWith<InvalidBotId> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidBotId> { store.save(testBot("/etc/passwd")) }
    }
}
