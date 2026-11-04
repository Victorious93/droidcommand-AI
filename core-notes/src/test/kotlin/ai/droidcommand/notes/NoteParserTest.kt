package ai.droidcommand.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteParserTest {
    @Test
    fun `frontmatter title tags and leftover keys`() {
        val raw = """
            ---
            title: Home Router
            tags:
              - network
              - Home
            aliases: Router
            ---

            The living-room AP. See [[Mesh]] and [[Mesh|the mesh]].
            Also #wifi.
        """.trimIndent()
        val note = NoteParser.parse("Network/router.md", raw)
        assertEquals("Home Router", note.title)
        assertEquals(setOf("network", "home", "wifi"), note.tags)
        assertEquals("Router", note.frontmatter.extras["aliases"])
        assertEquals(listOf("Mesh", "Mesh"), note.links.map { it.target })
        assertEquals("the mesh", note.links[1].alias)
        assertTrue(note.body.contains("living-room"))
    }

    @Test
    fun `inline tag list and heading title when frontmatter has none`() {
        val raw = """
            ---
            tags: [Work, #inbox]
            ---
            # Weekly review
            Nothing else.
        """.trimIndent()
        val note = NoteParser.parse("weekly.md", raw)
        assertEquals("Weekly review", note.title)
        assertEquals(setOf("work", "inbox"), note.tags)
    }

    @Test
    fun `no frontmatter uses the file stem and still finds wikilinks`() {
        val note = NoteParser.parse("ideas.md", "Talk to [[Alice]] about this.")
        assertEquals("ideas", note.title)
        assertEquals("Alice", note.links.single().target)
        assertTrue(note.tags.isEmpty())
    }

    @Test
    fun `wikilinks and hash tags inside a code fence are ignored`() {
        val raw = """
            Outside [[Kept]] and #kept

            ```
            [[Ignored]]
            #ignored
            ```
        """.trimIndent()
        val note = NoteParser.parse("fence.md", raw)
        assertEquals(listOf("Kept"), note.links.map { it.target })
        assertEquals(setOf("kept"), note.tags)
    }

    @Test
    fun `unclosed frontmatter is ordinary text and the file stem is the title`() {
        val raw = "---\nnot: yaml\n#hello\n"
        val note = NoteParser.parse("plain.md", raw)
        assertEquals("plain", note.title)
        assertTrue(note.body.contains("hello"))
    }
}
