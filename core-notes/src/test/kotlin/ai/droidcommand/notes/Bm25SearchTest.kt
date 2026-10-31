package ai.droidcommand.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bm25SearchTest {
    private fun notes(): List<NoteDocument> = listOf(
        NoteParser.parse(
            "Network/router.md",
            """
            ---
            title: Home Router
            tags: [network]
            ---
            The living-room access point is a Wi-Fi 6 router.
            """.trimIndent(),
        ),
        NoteParser.parse(
            "recipes.md",
            """
            ---
            title: Tomato soup
            tags: [food]
            ---
            Heat the tomatoes. Not a network device.
            """.trimIndent(),
        ),
        NoteParser.parse("empty.md", "---\ntitle: Empty\n---\n"),
    )

    @Test
    fun `router query ranks the router note first`() {
        val search = Bm25Search()
        search.index(notes())
        val hits = search.search("home router wifi")
        assertEquals("Network/router.md", hits.first().path)
        assertTrue(hits.first().score > 0.0)
        assertTrue(hits.none { it.path == "empty.md" })
    }

    @Test
    fun `a query of stopwords or blanks matches nothing`() {
        val search = Bm25Search()
        search.index(notes())
        assertEquals(emptyList(), search.search("the and for"))
        assertEquals(emptyList(), search.search("   "))
    }

    @Test
    fun `an empty index never throws`() {
        val search = Bm25Search()
        search.index(emptyList())
        assertEquals(emptyList(), search.search("router"))
    }
}
