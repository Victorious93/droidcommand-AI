package ai.droidcommand.agent

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileKnowledgeStoreTest {
    private fun newStore() = JsonFileKnowledgeStore(Files.createTempDirectory("droidcommand-knowledge-store-test"))

    private fun fixedEntry(id: String, content: String, tags: Set<String> = emptySet()): KnowledgeEntry {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        return KnowledgeEntry(id, content, "manual", tags, createdAt = at, updatedAt = at)
    }

    @Test
    fun `save then load returns an equal entry`() {
        val store = newStore()
        val entry = fixedEntry("fact-1", "The user prefers dark mode", tags = setOf("preferences"))

        store.save(entry)

        assertEquals(entry, store.load("fact-1"))
    }

    @Test
    fun `an entry survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-store-test")
        val entry = fixedEntry("fact-1", "The user prefers dark mode")
        JsonFileKnowledgeStore(dir).save(entry)

        assertEquals(entry, JsonFileKnowledgeStore(dir).load("fact-1"))
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `list returns every saved id, sorted`() {
        val store = newStore()
        store.save(fixedEntry("b", "content b"))
        store.save(fixedEntry("a", "content a"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save(fixedEntry("fact-1", "content"))

        assertTrue(store.delete("fact-1"))
        assertNull(store.load("fact-1"))
        assertEquals(false, store.delete("fact-1"))
    }

    @Test
    fun `findByTag and search genuinely re-read from a freshly reopened store, not a cache`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-store-test")
        JsonFileKnowledgeStore(dir).save(fixedEntry("a", "The user prefers Kotlin", tags = setOf("kotlin")))

        val reopened = JsonFileKnowledgeStore(dir)

        assertEquals(listOf("a"), reopened.findByTag("kotlin").map { it.id })
        assertEquals(listOf("a"), reopened.search("kotlin").map { it.id })
    }

    @Test
    fun `an id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidKnowledgeEntryId> { store.save(fixedEntry("../escape", "content")) }
        assertFailsWith<InvalidKnowledgeEntryId> { store.load("../escape") }
        assertFailsWith<InvalidKnowledgeEntryId> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidKnowledgeEntryId> { store.save(fixedEntry("/etc/passwd", "content")) }
    }
}
