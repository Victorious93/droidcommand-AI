package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
