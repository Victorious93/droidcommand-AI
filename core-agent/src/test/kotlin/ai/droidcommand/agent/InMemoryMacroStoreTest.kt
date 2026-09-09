package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryMacroStoreTest {
    @Test
    fun `save then load returns the same macro`() {
        val store = InMemoryMacroStore()
        val macro = Macro("greet", listOf(MacroStep("say", mapOf("text" to "hi"))))

        store.save(macro)

        assertEquals(macro, store.load("greet"))
    }

    @Test
    fun `load of an unsaved name returns null`() {
        val store = InMemoryMacroStore()
        assertNull(store.load("nope"))
    }

    @Test
    fun `list returns every saved macro name`() {
        val store = InMemoryMacroStore()
        store.save(Macro("b", emptyList()))
        store.save(Macro("a", emptyList()))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `save overwrites a macro with the same name`() {
        val store = InMemoryMacroStore()
        store.save(Macro("greet", listOf(MacroStep("say", mapOf("text" to "hi")))))
        store.save(Macro("greet", listOf(MacroStep("say", mapOf("text" to "bye")))))

        assertEquals(listOf(MacroStep("say", mapOf("text" to "bye"))), store.load("greet")!!.steps)
    }

    @Test
    fun `delete removes a macro and reports whether it existed`() {
        val store = InMemoryMacroStore()
        store.save(Macro("greet", emptyList()))

        assertTrue(store.delete("greet"))
        assertNull(store.load("greet"))
        assertEquals(false, store.delete("greet"))
    }
}
