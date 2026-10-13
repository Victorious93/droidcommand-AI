package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNoteVaultTest {
    private fun vault(): FileNoteVault {
        val root = Files.createTempDirectory("notes-vault")
        return FileNoteVault(root)
    }

    @Test
    fun `write list read and delete stay inside the vault`() {
        val vault = vault()
        assertEquals(listOf(""), vault.listFolders())
        val written = vault.write("meetings/today.md", "# Standup\n\nBring the notes.\n")
        assertEquals("Standup", written.title)
        assertEquals(listOf("", "meetings"), vault.listFolders())
        assertEquals(listOf("meetings/today.md"), vault.listNotes("meetings").map { it.path })
        assertEquals("Bring the notes.", vault.read("meetings/today.md")?.markdown?.lineSequence()?.map { it.trim() }?.first { it.isNotEmpty() && !it.startsWith("#") })
        assertTrue(vault.delete("meetings/today.md"))
        assertNull(vault.read("meetings/today.md"))
    }

    @Test
    fun `parent segments and absolute paths are refused`() {
        val vault = vault()
        assertFailsWith<IllegalArgumentException> { vault.write("../escape.md", "no") }
        assertFailsWith<IllegalArgumentException> { vault.write("/tmp/escape.md", "no") }
        assertFailsWith<IllegalArgumentException> { vault.write(".hidden/secret.md", "no") }
        assertFailsWith<IllegalArgumentException> { vault.read("notes.txt") }
        assertFailsWith<IllegalArgumentException> { vault.createFolder("..") }
    }

    @Test
    fun `empty folders can be created and removed and non-empty ones cannot`() {
        val vault = vault()
        vault.createFolder("projects/ideas")
        assertTrue(vault.listFolders().contains("projects/ideas"))
        assertFalse(vault.deleteFolder("projects"))
        assertTrue(vault.deleteFolder("projects/ideas"))
        assertTrue(vault.deleteFolder("projects"))
    }

    @Test
    fun `note file names gain a markdown suffix`() {
        assertEquals("today.md", noteFileName("today"))
        assertEquals("today.md", noteFileName("today.md"))
        assertFailsWith<IllegalArgumentException> { noteFileName("../x") }
        assertFailsWith<IllegalArgumentException> { noteFileName(".secret") }
    }
}
