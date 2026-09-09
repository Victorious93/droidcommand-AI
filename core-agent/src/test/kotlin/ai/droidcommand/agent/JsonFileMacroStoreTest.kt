package ai.droidcommand.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileMacroStoreTest {
    private fun newStore() = JsonFileMacroStore(Files.createTempDirectory("droidcommand-macro-store-test"))

    @Test
    fun `save then load returns an equal macro`() {
        val store = newStore()
        val macro = Macro("greet-twice", listOf(MacroStep("say", mapOf("text" to "hi")), MacroStep("say", mapOf("text" to "bye"))))

        store.save(macro)

        assertEquals(macro, store.load("greet-twice"))
    }

    @Test
    fun `a macro survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-macro-store-test")
        val macro = Macro("greet", listOf(MacroStep("say", mapOf("text" to "hi"))))
        JsonFileMacroStore(dir).save(macro)

        val reopened = JsonFileMacroStore(dir)

        assertEquals(macro, reopened.load("greet"))
    }

    @Test
    fun `load of an unsaved name returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `list returns every saved macro name, sorted`() {
        val store = newStore()
        store.save(Macro("b", emptyList()))
        store.save(Macro("a", emptyList()))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save(Macro("greet", emptyList()))

        assertTrue(store.delete("greet"))
        assertNull(store.load("greet"))
        assertEquals(false, store.delete("greet"))
    }

    @Test
    fun `a name containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidMacroName> { store.save(Macro("../escape", emptyList())) }
        assertFailsWith<InvalidMacroName> { store.load("../escape") }
        assertFailsWith<InvalidMacroName> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped name is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidMacroName> { store.save(Macro("/etc/passwd", emptyList())) }
    }
}
