package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryKnowledgeStoreTest {
    @Test
    fun `save then load returns an equal entry`() {
        val store = InMemoryKnowledgeStore()
        val entry = KnowledgeEntry(id = "fact-1", content = "The user prefers dark mode", source = "manual", tags = setOf("preferences"))

        store.save(entry)

        assertEquals(entry, store.load("fact-1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(InMemoryKnowledgeStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("b", "content b", "manual"))
        store.save(KnowledgeEntry("a", "content a", "manual"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes an entry and reports whether it existed`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("fact-1", "content", "manual"))

        assertTrue(store.delete("fact-1"))
        assertNull(store.load("fact-1"))
        assertEquals(false, store.delete("fact-1"))
    }

    @Test
    fun `saving an entry with the same id overwrites the previous one`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("fact-1", "first version", "manual"))
        store.save(KnowledgeEntry("fact-1", "second version", "manual"))

        assertEquals("second version", store.load("fact-1")!!.content)
    }

    @Test
    fun `findByTag matches only entries carrying that exact tag, case-sensitively`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("a", "content a", "manual", tags = setOf("kotlin")))
        store.save(KnowledgeEntry("b", "content b", "manual", tags = setOf("Kotlin")))
        store.save(KnowledgeEntry("c", "content c", "manual", tags = setOf("java")))

        assertEquals(listOf("a"), store.findByTag("kotlin").map { it.id })
    }

    @Test
    fun `findByTag results are sorted by id`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("z", "content z", "manual", tags = setOf("x")))
        store.save(KnowledgeEntry("a", "content a", "manual", tags = setOf("x")))

        assertEquals(listOf("a", "z"), store.findByTag("x").map { it.id })
    }

    @Test
    fun `search matches a literal substring case-insensitively`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("a", "The user prefers Kotlin over Java", "manual"))

        assertEquals(listOf("a"), store.search("kotlin").map { it.id })
    }

    @Test
    fun `search does not match on meaning, only literal substrings`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry("a", "The user prefers Kotlin", "manual"))

        assertEquals(emptyList(), store.search("programming language"))
    }
}
