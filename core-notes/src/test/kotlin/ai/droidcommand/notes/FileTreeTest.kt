package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTreeTest {
    private fun vault(): NoteVault = NoteVault(Files.createTempDirectory("file-tree"))

    @Test
    fun `build nests folders and lists notes under them`() {
        val vault = vault()
        vault.createFolder("work/deep")
        vault.write("work/deep/a.md", "a")
        vault.write("ideas.md", "ideas")
        val tree = FileTree.build(vault)
        assertEquals(listOf("work", "ideas.md"), tree.map { it.name })
        val work = tree.single { it.folder }
        assertEquals(listOf("deep"), work.children.map { it.name })
        assertEquals(listOf("a.md"), work.children.single().children.map { it.name })
        assertEquals("work/deep/a.md", work.children.single().children.single().relativePath)
    }

    @Test
    fun `visible skips children of a collapsed folder`() {
        val vault = vault()
        vault.createFolder("work")
        vault.write("work/a.md", "a")
        vault.write("root.md", "r")
        val tree = FileTree.build(vault)
        val collapsed = FileTree.visible(tree, expanded = emptySet())
        assertEquals(listOf("work", "root.md"), collapsed.map { it.first.name })
        val open = FileTree.visible(tree, expanded = setOf("work"))
        assertEquals(listOf("work", "a.md", "root.md"), open.map { it.first.name })
        assertEquals(1, open.single { it.first.name == "a.md" }.second)
    }

    @Test
    fun `folderPaths walks every folder`() {
        val vault = vault()
        vault.createFolder("a/b")
        vault.write("a/b/c.md", "c")
        assertEquals(listOf("a", "a/b"), FileTree.folderPaths(FileTree.build(vault)))
        assertTrue(FileTree.build(vault).isNotEmpty())
    }
}
