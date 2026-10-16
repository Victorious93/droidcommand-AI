package ai.droidcommand.app.ui.notes

import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.VaultEntry
import java.nio.file.Files
import java.nio.file.Path
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
    fun `a new note is created empty and opened`() {
        val viewModel = NotesViewModel(root)

        viewModel.newNote("ideas")

        assertNull(viewModel.error)
        assertEquals("ideas.md", viewModel.openPath)
        assertEquals("", viewModel.body)
        assertTrue(vault.exists("ideas.md"))
        assertFalse(viewModel.unsavedChanges)
    }
}
