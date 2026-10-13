package ai.droidcommand.vault

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMarkdownVaultTest {
    @Test
    fun `folders and notes stay inside the vault and round trip markdown`() {
        val root = Files.createTempDirectory("vault")
        val vault = FileMarkdownVault(root)
        val folder = vault.createFolder("Projects", null)
        val note = vault.createNote(folder.id, "Theme notes", "# Blue\n\nDarker surfaces.")
        assertEquals("Projects/Theme notes.md", note.id)
        assertEquals("# Blue\n\nDarker surfaces.", vault.note(note.id)?.markdown)

        val renamed = vault.updateNote(note.id, "Theme notes", "# Navy\n")
        assertEquals("# Navy\n", renamed.markdown)
        assertEquals(listOf("Theme notes"), vault.notes(folder.id).map { it.title })

        assertFailsWith<VaultChangeRejected> { vault.createFolder("../outside", null) }
        assertFailsWith<VaultChangeRejected> { vault.createNote(null, "a/b", "nope") }
        assertNull(vault.note("../secret.md"))

        vault.deleteNote(renamed.id)
        vault.deleteFolder(folder.id)
        assertTrue(vault.folders().isEmpty())
        assertFailsWith<VaultChangeRejected> { vault.deleteFolder(folder.id) }
    }

    @Test
    fun `a folder with a note cannot be deleted`() {
        val vault = FileMarkdownVault(Files.createTempDirectory("vault-busy"))
        val folder = vault.createFolder("Keep", null)
        vault.createNote(folder.id, "Stay", "text")
        assertFailsWith<VaultChangeRejected> { vault.deleteFolder(folder.id) }
    }
}
