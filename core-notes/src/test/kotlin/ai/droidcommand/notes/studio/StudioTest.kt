package ai.droidcommand.notes.studio

import ai.droidcommand.notes.NoteVault
import java.net.InetAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextFilesTest {
    @Test
    fun `render and parse round trip`() {
        val doc = ContextTemplates.all.first().document
        val parsed = ContextDocument.parse(doc.render())
        assertEquals(doc.title, parsed.title)
        assertEquals(doc.sections.map { it.title }, parsed.sections.map { it.title })
        assertEquals(doc.render(), parsed.render())
    }

    @Test
    fun `heading inside code fence is not a section`() {
        val parsed = ContextDocument.parse("# T\n## A\n```\n## not\n```\n## B\nx")
        assertEquals(listOf("A", "B"), parsed.sections.map { it.title })
    }

    @Test
    fun `kind is detected from file name`() {
        assertEquals(ContextFileKind.COPILOT_INSTRUCTIONS, ContextFileKind.fromFileName(".github/copilot-instructions.md"))
        assertEquals(ContextFileKind.CURSOR_RULES, ContextFileKind.fromFileName(".cursorrules"))
        assertNull(ContextFileKind.fromFileName("notes.md"))
    }

    @Test
    fun `every kind has a template that lints without errors`() {
        ContextFileKind.entries.forEach { kind ->
            val t = ContextTemplates.forKind(kind).single()
            val errors = ContextLinter.lint(t.document.render(), kind).filter { it.severity == LintSeverity.ERROR }
            assertTrue(errors.isEmpty(), "$kind: $errors")
        }
    }

    @Test
    fun `linter flags secrets, unclosed fences, duplicates and empty files`() {
        val rules = ContextLinter.lint("# A\n## Build\nrun test\n## build\napi_key = abcdefghijkl\n```\ncode", ContextFileKind.CLAUDE_MD).map { it.rule }
        assertTrue("secret" in rules)
        assertTrue("unclosed-fence" in rules)
        assertTrue("duplicate-heading" in rules)
        assertEquals("empty", ContextLinter.lint("  ", null).single().rule)
    }
}

class PromptVaultTest {
    @Test
    fun `save search delete`() {
        val dir = Files.createTempDirectory("pv")
        var n = 0
        val vault = PromptVault(dir.resolve("p.txt")) { "id${n++}" }
        vault.save("Review", "Code", "Review this diff\nline two")
        vault.save("Summary", "Writing", "Summarize")
        assertEquals(listOf("Code", "Writing"), vault.categories())
        assertEquals("Review", vault.search("diff").single().title)
        assertEquals("Summary", vault.search("", "Writing").single().title)
        assertEquals("Review this diff\nline two", PromptVault(dir.resolve("p.txt")).all().first { it.id == "id0" }.body)
        assertTrue(vault.delete("id0"))
        assertFalse(vault.delete("id0"))
        assertEquals(1, vault.all().size)
    }
}

class MarkdownFormatterTest {
    @Test
    fun `bold wraps selection`() {
        val r = MarkdownFormatter.apply(TextEdit("say hi", 4, 6), FormatAction.BOLD)
        assertEquals("say **hi**", r.text)
        assertEquals("hi", r.text.substring(r.start, r.end))
    }

    @Test
    fun `bullet toggles each selected line`() {
        val r = MarkdownFormatter.apply(TextEdit("a\nb", 0, 3), FormatAction.BULLET)
        assertEquals("- a\n- b", r.text)
        assertEquals("a\nb", MarkdownFormatter.apply(TextEdit(r.text, 0, r.text.length), FormatAction.BULLET).text)
    }

    @Test
    fun `mermaid block inserts a starter diagram on its own line`() {
        val r = MarkdownFormatter.apply(TextEdit("text", 4, 4), FormatAction.MERMAID)
        assertTrue(r.text.startsWith("text\n```mermaid\n"))
        assertTrue(MermaidPreview.hasDiagram(r.text))
    }
}

class MermaidPreviewTest {
    @Test
    fun `splits mermaid fences and escapes html`() {
        val parts = MermaidPreview.split("# Hi <b>\n```mermaid\ngraph TD\nA-->B\n```\nafter")
        assertEquals(3, parts.size)
        assertEquals("graph TD\nA-->B", (parts[1] as MermaidPreview.Part.Diagram).source)
        val page = MermaidPreview.page("<script>alert(1)</script>\n```mermaid\nA-->B\n```")
        assertFalse(page.contains("<script>alert"))
        assertTrue(page.contains("A--&gt;B"))
        assertTrue(page.contains("default-src 'none'"))
        assertTrue(page.contains(MermaidPreview.SCRIPT_URL))
    }

    @Test
    fun `mermaid inside another fence stays code`() {
        assertFalse(MermaidPreview.hasDiagram("````\n```mermaid\nx\n```\n````"))
    }
}

class LanVaultTest {
    @Test
    fun `default is off and only LAN addresses pass`() {
        assertFalse(LanVaultPolicy.DEFAULT_ENABLED)
        assertTrue(LanVaultPolicy.isLanAddress(InetAddress.getByName("192.168.1.20")))
        assertTrue(LanVaultPolicy.isLanAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(LanVaultPolicy.isLanAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(LanVaultPolicy.isLanAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanVaultPolicy.isLanAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun `password rules and basic auth`() {
        assertTrue(LanVaultPolicy.passwordProblem("short") != null)
        assertNull(LanVaultPolicy.passwordProblem("long-enough"))
        val h = "Basic " + Base64.getEncoder().encodeToString("vault:long-enough".toByteArray())
        assertTrue(LanVaultPolicy.checkAuth(h, "long-enough"))
        assertFalse(LanVaultPolicy.checkAuth(h, "other-password"))
        assertFalse(LanVaultPolicy.checkAuth(null, "long-enough"))
    }

    @Test
    fun `routes are read only and refuse escapes`() {
        val dir = Files.createTempDirectory("lan")
        val vault = NoteVault(dir)
        vault.write("a.md", "# A\n<script>x</script>")
        val server = LanVaultServer({ vault }, "long-enough")
        assertTrue(server.route("/").third.contains("a.md"))
        val note = server.route("/note/a.md")
        assertEquals(200, note.first)
        assertFalse(note.third.contains("<script>x"))
        assertEquals(400, server.route("/raw/../secret.md").first)
        assertEquals(404, server.route("/delete/a.md").first)
    }

    @Test
    fun `server refuses public bind and serves with auth over loopback`() {
        val dir = Files.createTempDirectory("lan2")
        val vault = NoteVault(dir).also { it.write("n.md", "hello") }
        val server = LanVaultServer({ vault }, "long-enough")
        kotlin.test.assertFailsWith<IllegalArgumentException> { server.start(InetAddress.getByName("0.0.0.0"), 0) }
        server.start(InetAddress.getLoopbackAddress(), 0)
        try {
            val url = java.net.URI("http://127.0.0.1:${server.port}/raw/n.md").toURL()
            val noAuth = url.openConnection() as java.net.HttpURLConnection
            assertEquals(401, noAuth.responseCode)
            val ok = url.openConnection() as java.net.HttpURLConnection
            ok.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("vault:long-enough".toByteArray()))
            assertEquals(200, ok.responseCode)
            assertEquals("hello", ok.inputStream.readBytes().toString(Charsets.UTF_8))
            val post = url.openConnection() as java.net.HttpURLConnection
            post.requestMethod = "DELETE"
            post.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("vault:long-enough".toByteArray()))
            assertEquals(405, post.responseCode)
        } finally {
            server.stop()
        }
    }
}
