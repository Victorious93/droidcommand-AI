package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteIndexTest {
    private fun vault(): NoteVault = NoteVault(Files.createTempDirectory("brain-index"))

    @Test
    fun `backlinks resolve by title stem and path`() {
        val vault = vault()
        vault.write(
            "projects.md",
            """
            ---
            title: Projects
            ---
            Link to [[Home Router]] and [[inbox]].
            """.trimIndent(),
        )
        vault.write(
            "Network/router.md",
            """
            ---
            title: Home Router
            ---
            The AP. Also see [[projects]].
            """.trimIndent(),
        )
        vault.createFolder("daily")
        vault.write("daily/inbox.md", "Capture bin.")

        val snap = NoteIndex(vault).refresh()
        assertEquals(3, snap.notes.size)
        assertEquals("Network/router.md", snap.resolveLink("Home Router")?.relativePath)
        assertEquals("daily/inbox.md", snap.resolveLink("inbox")?.relativePath)
        assertEquals(listOf("projects.md"), snap.backlinksTo("Network/router.md").map { it.relativePath })
        assertEquals(listOf("projects.md"), snap.backlinksTo("daily/inbox.md").map { it.relativePath })
        assertEquals(listOf("Network/router.md"), snap.backlinksTo("projects.md").map { it.relativePath })
    }

    @Test
    fun `an unknown wikilink does not invent a backlink`() {
        val vault = vault()
        vault.write("a.md", "See [[Missing Note]].")
        val snap = NoteIndex(vault).refresh()
        assertTrue(snap.backlinks.values.all { it.isEmpty() } || snap.backlinks.isEmpty())
        assertEquals(null, snap.resolveLink("Missing Note"))
    }
}
