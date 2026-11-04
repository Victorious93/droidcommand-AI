package ai.droidcommand.notes.studio

import ai.droidcommand.notes.NoteVault
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiContextTest {
    @Test
    fun `templates lint clean of errors and round-trip through the structured editor`() {
        AiContextKind.entries.forEach { kind ->
            val text = AiContextTemplates.template(kind, "Demo")
            assertTrue(AiContextLinter.lint(kind, text).none { it.severity == LintSeverity.ERROR }, kind.name)
            val doc = ContextDocument.parse(text)
            assertEquals(ContextDocument.parse(doc.render()), doc)
        }
    }

    @Test
    fun `linter flags empty, secrets, duplicates and placeholders`() {
        assertEquals(LintSeverity.ERROR, AiContextLinter.lint(AiContextKind.CLAUDE, " ").single().severity)
        val issues = AiContextLinter.lint(AiContextKind.AGENTS, "# X\n## Rules\nTODO\napi_key = abcdefghijklmnop1234\n## rules\n")
        assertTrue(issues.any { it.severity == LintSeverity.ERROR && it.line == 4 })
        assertTrue(issues.any { it.message.startsWith("Duplicate") && it.line == 5 })
        assertTrue(issues.any { it.message.startsWith("Placeholder") && it.line == 3 })
        assertTrue(AiContextLinter.lint(AiContextKind.CLAUDE, "just text").any { "no headings" in it.message })
    }

    @Test
    fun `structured editor edits sections and vault paths avoid hidden names`() {
        val doc = ContextDocument.parse("# T\n\n## A\n\none\n").addSection("B").withSection(1, ContextSection("B", "two"))
        assertEquals("# T\n\n## A\n\none\n\n## B\n\ntwo\n", doc.render())
        assertEquals("# T\n\n## B\n\ntwo\n", doc.removeSection(0).render())
        assertEquals("AI Context/cursorrules.md", AiContextKind.CURSOR_RULES.vaultPath)
        assertEquals(".github/copilot-instructions.md", AiContextKind.COPILOT.repoPath)
    }
}

class PromptVaultTest {
    @Test
    fun `save, list, search and delete`() {
        val vault = NoteVault(Files.createTempDirectory("pv"))
        val prompts = PromptVault(vault)
        prompts.save("Coding", "Review diff", "Review this diff for bugs")
        prompts.save("", "../escape", "x")
        prompts.save("Writing", "Summarize", "Summarize in three bullets")
        assertEquals(listOf("Coding", "General", "Writing"), prompts.categories())
        assertEquals("Review diff", prompts.search("diff bugs").single().title)
        assertEquals(1, prompts.search("", "Writing").size)
        assertTrue(prompts.all().all { it.path.startsWith("Prompts/") })
        prompts.delete(prompts.search("summarize").single())
        assertEquals(2, prompts.all().size)
        assertFailsWith<IllegalArgumentException> { prompts.save("x", "  ", "y") }
    }
}

class MarkdownFormattingTest {
    @Test
    fun `wrap and toggle prefixes`() {
        val bold = MarkdownFormatting.apply(FormatAction.BOLD, TextEdit("say hi", 4, 6))
        assertEquals("say **hi**", bold.text)
        assertEquals(6 to 8, bold.start to bold.end)
        assertEquals("**bold**", MarkdownFormatting.apply(FormatAction.BOLD, TextEdit("", 0, 0)).text)
        val listed = MarkdownFormatting.apply(FormatAction.BULLET, TextEdit("a\nb", 0, 3))
        assertEquals("- a\n- b", listed.text)
        assertEquals("a\nb", MarkdownFormatting.apply(FormatAction.BULLET, listed).text)
        assertEquals("1. a\n2. b", MarkdownFormatting.apply(FormatAction.NUMBERED, TextEdit("a\nb", 0, 3)).text)
        assertEquals("## t", MarkdownFormatting.apply(FormatAction.H2, TextEdit("# t", 1, 1)).text)
        assertTrue(MarkdownFormatting.apply(FormatAction.MERMAID, TextEdit("x", 1, 1)).text.startsWith("x\n```mermaid\n"))
    }
}

class MermaidHtmlTest {
    @Test
    fun `escapes html, loads only the bundled script, and finds diagrams`() {
        val md = "# Hi <b>\n<script>alert(1)</script>\n```mermaid\ngraph TD\n A-->B\n```\n"
        val html = MermaidHtml.page(md, dark = true)
        assertFalse("<script>alert" in html)
        assertTrue("&lt;script&gt;" in html)
        assertTrue(MermaidHtml.SCRIPT_URL in html)
        assertFalse("http://" in html || "https://" in html)
        assertTrue("default-src 'none'" in html)
        assertEquals(listOf("graph TD\n A--&gt;B".replace("&gt;", ">")), MermaidHtml.diagrams(md))
        assertFalse(MermaidHtml.SCRIPT_URL in MermaidHtml.page("plain", false))
    }
}

class LanVaultServerTest {
    private fun get(port: Int, path: String, password: String?, method: String = "GET"): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (password != null) c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("u:$password".toByteArray()))
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString() ?: "" }.getOrDefault("")
        return code to body
    }

    @Test
    fun `requires the password, serves read-only, refuses writes`() {
        val vault = NoteVault(Files.createTempDirectory("lan"))
        vault.write("hello.md", "# Hello\n")
        val server = LanVaultServer(vault, "correct horse", InetAddress.getLoopbackAddress(), port = 0)
        assertFalse(server.isRunning)
        server.start()
        try {
            val port = server.boundPort
            assertEquals(401, get(port, "/", null).first)
            assertEquals(401, get(port, "/", "wrong password").first)
            val index = get(port, "/", "correct horse")
            assertEquals(200, index.first)
            assertTrue("hello.md" in index.second)
            assertTrue("<h1>Hello</h1>" in get(port, "/note/hello.md", "correct horse").second)
            assertEquals(404, get(port, "/note/..%2F..%2Fetc%2Fpasswd.md", "correct horse").first)
            assertEquals(405, get(port, "/note/hello.md", "correct horse", method = "DELETE").first)
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning)
    }

    @Test
    fun `short passwords and public bind addresses are refused`() {
        val vault = NoteVault(Files.createTempDirectory("lan2"))
        assertFailsWith<IllegalArgumentException> { LanVaultServer(vault, "short", InetAddress.getLoopbackAddress()) }
        assertFailsWith<IllegalArgumentException> { LanVaultServer(vault, "long enough pw", InetAddress.getByName("8.8.8.8")) }
        assertTrue(LanVaultServer.isLanAddress(InetAddress.getByName("192.168.1.5")))
        assertFalse(LanVaultServer.isLanAddress(InetAddress.getByName("1.1.1.1")))
    }
}
