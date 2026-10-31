package ai.droidcommand.app.ui.notes

import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.VaultEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesViewModelTest {
    private val root: Path = Files.createTempDirectory("notes-vm")
    private val vault = NoteVault(root)

    private fun folder(path: String) = VaultEntry(path.substringAfterLast('/'), path, folder = true)

    private fun note(path: String) = VaultEntry(path.substringAfterLast('/'), path, folder = false)

    @Test
    fun `going up from an edited note saves it and shows the folder it was in`() {
        vault.createFolder("work")
        vault.write("work/a.md", "old text")
        val viewModel = NotesViewModel(root)
        viewModel.open(folder("work"))
        viewModel.open(note("work/a.md"))

        viewModel.edit("new text")
        assertTrue(viewModel.unsavedChanges)
        viewModel.up()

        assertEquals("new text", vault.read("work/a.md"))
        assertNull(viewModel.openPath)
        assertEquals("work", viewModel.folder)
        assertEquals(listOf("a.md"), viewModel.entries.map { it.name })
        assertFalse(viewModel.unsavedChanges)
        assertNull(viewModel.error)
    }

    @Test
    fun `going up from a folder listing goes to the parent folder`() {
        vault.createFolder("a/b")
        val viewModel = NotesViewModel(root)
        viewModel.open(folder("a"))
        viewModel.open(folder("a/b"))
        assertEquals("a/b", viewModel.folder)

        viewModel.up()
        assertEquals("a", viewModel.folder)
        viewModel.up()
        assertEquals("", viewModel.folder)
    }

    @Test
    fun `opening a note that disappeared reports it and does not crash`() {
        vault.write("gone.md", "x")
        val viewModel = NotesViewModel(root)
        val entry = viewModel.entries.single()
        Files.delete(root.resolve("gone.md"))

        viewModel.open(entry)

        assertNull(viewModel.openPath)
        assertTrue(viewModel.error.orEmpty().startsWith("Could not open gone.md"), viewModel.error)
    }

    @Test
    fun `a failed save keeps the note open and keeps the edits`() {
        vault.write("a.md", "on disk")
        val viewModel = NotesViewModel(root)
        viewModel.open(note("a.md"))
        viewModel.edit("typed but not saved")
        // Make the write fail: the note's path is now a non-empty directory, which a file cannot be moved over.
        Files.delete(root.resolve("a.md"))
        Files.createDirectory(root.resolve("a.md"))
        Files.write(root.resolve("a.md").resolve("blocker"), "x".toByteArray())

        assertFalse(viewModel.save())
        assertNotNull(viewModel.error)
        viewModel.up()

        // Going up must not throw the edits away when they could not be saved.
        assertEquals("a.md", viewModel.openPath)
        assertEquals("typed but not saved", viewModel.body)
        assertTrue(viewModel.unsavedChanges)
        // No half-written temporary file is left behind either.
        assertEquals(emptyList(), Files.list(root).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.filter { it.startsWith(".saving-") }.toList() })
    }

    @Test
    fun `a new note will not overwrite an existing one`() {
        vault.write("a.md", "keep me")
        val viewModel = NotesViewModel(root)

        viewModel.newNote("a")
        assertTrue(viewModel.error.orEmpty().contains("already exists"), viewModel.error)
        assertEquals("keep me", vault.read("a.md"))
        assertNull(viewModel.openPath)

        viewModel.newNote("a.md")
        assertEquals("keep me", vault.read("a.md"))
    }

    @Test
    fun `search ranks a matching note and opening it shows backlinks`() {
        vault.write(
            "router.md",
            """
            ---
            title: Home Router
            ---
            Living-room Wi-Fi.
            """.trimIndent(),
        )
        vault.write("index.md", "See [[Home Router]].")
        val viewModel = NotesViewModel(root)
        viewModel.search("home router wifi")
        assertEquals("router.md", viewModel.hits.first().path)

        viewModel.openHit(viewModel.hits.first())
        assertEquals("router.md", viewModel.openPath)
        assertTrue(viewModel.openBacklinks.any { it.contains("index", ignoreCase = true) || it.contains("See") || it == "index" })
    }

    @Test
    fun `a new note is created empty and opened`() {
        val viewModel = NotesViewModel(root)

        viewModel.newNote("ideas")

        assertNull(viewModel.error)
        assertEquals("ideas.md", viewModel.openPath)
        assertEquals("", viewModel.body)
        assertTrue(vault.exists("ideas.md"))
        assertFalse(viewModel.unsavedChanges)
    }

    @Test
    fun `today creates a Daily note and does not overwrite an existing one`() {
        val clock = Clock.fixed(Instant.parse("2026-10-30T15:00:00Z"), ZoneOffset.UTC)
        vault.createFolder("Daily")
        vault.write("Daily/2026-10-30.md", "already here")
        val viewModel = NotesViewModel(root, clock = clock)

        viewModel.openToday()

        assertEquals("Daily/2026-10-30.md", viewModel.openPath)
        assertEquals("already here", vault.read("Daily/2026-10-30.md"))
    }

    @Test
    fun `today writes a tagged daily note when the day is new`() {
        val clock = Clock.fixed(Instant.parse("2026-10-30T15:00:00Z"), ZoneOffset.UTC)
        val viewModel = NotesViewModel(root, clock = clock)

        viewModel.openToday()

        assertEquals("Daily/2026-10-30.md", viewModel.openPath)
        assertTrue(vault.read("Daily/2026-10-30.md").contains("daily"))
        assertTrue(viewModel.tree.any { it.name == "Daily" && it.folder })
        assertTrue(viewModel.graph.nodes.any { it.path == "Daily/2026-10-30.md" })
    }

    @Test
    fun `file tree nests folders and the graph lists a wikilink as an edge`() {
        vault.createFolder("work")
        vault.write("work/a.md", "See [[ideas]].")
        vault.write("ideas.md", "# ideas")
        val viewModel = NotesViewModel(root)

        val work = viewModel.tree.single { it.name == "work" }
        assertEquals(listOf("a.md"), work.children.map { it.name })
        assertEquals("ideas.md", viewModel.graph.edges.single().to)
        viewModel.showPane(NotesPane.GRAPH)
        assertEquals(NotesPane.GRAPH, viewModel.pane)
        viewModel.openGraphNode("ideas.md")
        assertEquals("ideas.md", viewModel.openPath)
        assertTrue(viewModel.openBacklinks.isNotEmpty())
    }

    @Test
    fun `AI read write stays off without a runtime`() {
        val viewModel = NotesViewModel(root)
        assertFalse(viewModel.aiCanReadWrite)
        viewModel.setAiCanReadWrite(true)
        assertFalse(viewModel.aiCanReadWrite)
    }
}
