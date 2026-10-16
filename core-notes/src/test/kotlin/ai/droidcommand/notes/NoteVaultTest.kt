package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteVaultTest {
    private fun vault(): NoteVault = NoteVault(Files.createTempDirectory("notes"))

    @Test
    fun `a fresh vault has folders, notes, and no plugins`() {
        val vault = vault()
        assertEquals(emptyList(), vault.list())
        assertEquals(emptyList(), PluginStore(vault.root).list())
        vault.createFolder("work")
        vault.write("work/a.md", "# Hello\n\n- one\n\n```\ncode\n```\n")
        assertEquals(listOf("work"), vault.list().map { it.name })
        assertEquals("a.md", vault.list("work").single().name)
        val blocks = renderMarkdown(vault.read("work/a.md"))
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks.any { it is MarkdownBlock.Bullet })
        assertTrue(blocks.any { it is MarkdownBlock.Code })
    }

    @Test
    fun `paths that leave the vault are refused`() {
        val vault = vault()
        assertFailsWith<IllegalArgumentException> { vault.write("../outside.md", "x") }
        assertFailsWith<IllegalArgumentException> { vault.write("/tmp/x.md", "x") }
        assertFailsWith<IllegalArgumentException> { vault.read(".plugins/secret.md") }
        assertFailsWith<IllegalArgumentException> { vault.write("note.txt", "x") }
    }

    @Test
    fun `plugins install disabled and only enabled ones change text`() {
        val vault = vault()
        val store = PluginStore(vault.root)
        val installed = store.install(
            """
            id: shout
            name: Shout
            enabled: true
            prefix: [!]
            replace: TODO -> done
            """.trimIndent(),
        )
        assertEquals(false, installed.enabled)
        assertEquals("TODO stays", store.apply("TODO stays"))
        store.setEnabled("shout", true)
        assertEquals("[!]done stays", store.apply("TODO stays"))
        store.setEnabled("shout", false)
        assertEquals("TODO stays", store.apply("TODO stays"))
        store.remove("shout")
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `a manifest is literal text, not a script`() {
        assertFailsWith<IllegalArgumentException> {
            PluginManifest.parse("id: Bad_Name\nname: No\n")
        }
        val plugin = PluginManifest.parse("id: ok\nname: Ok\nreplace: a.* -> b\n")
        assertEquals("a.*" to "b", plugin.replacements.single())
    }

    @Test
    fun `saving replaces the text and leaves no temporary file behind`() {
        val vault = vault()
        vault.write("a.md", "first")
        vault.write("a.md", "second, and longer than the first")
        assertEquals("second, and longer than the first", vault.read("a.md"))
        val names = Files.list(vault.root).use { stream -> stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf(".plugins", "a.md"), names)
    }

    @Test
    fun `a temporary file left by a crash is never listed as a note`() {
        val vault = vault()
        vault.write("a.md", "x")
        Files.write(vault.root.resolve(".saving-123.tmp"), "half a note".toByteArray())
        assertEquals(listOf("a.md"), vault.list().map { it.name })
    }

    @Test
    fun `exists tells a new note from a saved one and still refuses a path outside the vault`() {
        val vault = vault()
        assertFalse(vault.exists("a.md"))
        vault.write("a.md", "x")
        assertTrue(vault.exists("a.md"))
        assertFailsWith<IllegalArgumentException> { vault.exists("../a.md") }
    }
}
