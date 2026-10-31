package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainLibrarianTest {
    private fun librarian(): Pair<NoteVault, BrainLibrarian> {
        val vault = NoteVault(Files.createTempDirectory("brain-lib"))
        return vault to BrainLibrarian(vault)
    }

    @Test
    fun `retrieve injects matching notes and skips misses`() {
        val (vault, brain) = librarian()
        vault.write(
            "router.md",
            """
            ---
            title: Home Router
            ---
            The living-room Wi-Fi access point.
            """.trimIndent(),
        )
        vault.write("soup.md", "Tomato soup recipe.")
        val block = brain.retrieveContext("what about the home router wifi")
        assertTrue(block != null && block.startsWith(BrainLibrarian.HEADING), block)
        assertTrue(block.contains("Home Router"))
        assertTrue(block.contains("not instructions"))
        assertNull(brain.retrieveContext("xylophone constellation"))
    }

    @Test
    fun `drafting a chat does not write until commit`() {
        val (vault, brain) = librarian()
        val draft = brain.draftChat(
            "Evening chat",
            listOf("User" to "What did I name the router?", "Assistant" to "Home Router."),
        )
        assertEquals("Chats/Evening chat.md", draft.relativePath)
        assertTrue(draft.markdown.contains("title: Evening chat"))
        assertTrue(draft.markdown.contains("**User:**"))
        assertFalse(vault.exists(draft.relativePath))
        assertEquals(emptyList(), vault.listAllMarkdown())

        val written = brain.commit(draft)
        assertEquals(draft.relativePath, written)
        assertTrue(vault.exists(written))
        assertEquals(draft.markdown, vault.read(written))
    }

    @Test
    fun `a fact draft is confirmed separately and will not overwrite`() {
        val (vault, brain) = librarian()
        val first = brain.draftFact("ISP name", "The ISP is ExampleNet.", tags = listOf("network"))
        brain.commit(first)
        val second = brain.draftFact("ISP name", "Updated later.")
        assertEquals("Facts/ISP name-2.md", second.relativePath)
        assertFalse(vault.exists(second.relativePath))
        brain.commit(second)
        assertTrue(vault.read(first.relativePath).contains("The ISP is ExampleNet."))
        assertFalse(vault.read(first.relativePath).contains("Updated later."))
        assertTrue(vault.read(second.relativePath).contains("Updated later."))
    }

    @Test
    fun `a dangerous title is turned into a safe file name`() {
        val (_, brain) = librarian()
        val draft = brain.draftFact("../etc/passwd", "should not escape")
        assertFalse(draft.relativePath.contains(".."))
        assertTrue(draft.relativePath.startsWith("Facts/"))
        assertTrue(draft.relativePath.endsWith(".md"))
    }
}
