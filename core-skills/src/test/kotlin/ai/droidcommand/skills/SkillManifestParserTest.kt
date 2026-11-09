package ai.droidcommand.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillManifestParserTest {
    private val parser = SkillManifestParser()

    @Test
    fun `parses frontmatter with an inline tools list and a body`() {
        val doc = """
            ---
            name: summarize
            description: Summarize a document into bullet points
            tools: [read_file, web_search]
            version: 1.2
            ---
            # Summarize
            Read the document and produce at most five bullets.
        """.trimIndent()

        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals("summarize", manifest.name)
        assertEquals("Summarize a document into bullet points", manifest.description)
        assertEquals(listOf("read_file", "web_search"), manifest.declaredTools)
        assertEquals("1.2", manifest.version)
        assertTrue(manifest.instructions.startsWith("# Summarize"))
        assertTrue(manifest.instructions.endsWith("five bullets."))
    }

    @Test
    fun `parses a block-style tools list`() {
        val doc = """
            ---
            name: triage
            description: Triage incoming reports
            tools:
              - read_file
              - run_shell
            ---
            Body.
        """.trimIndent()
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals(listOf("read_file", "run_shell"), manifest.declaredTools)
    }

    @Test
    fun `tolerates CRLF line endings and leading blank lines`() {
        val doc = "\r\n---\r\nname: x\r\ndescription: A skill\r\n---\r\nBody line\r\n"
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals("x", manifest.name)
        assertEquals("Body line", manifest.instructions)
    }

    @Test
    fun `defaults tools to empty and version to null when absent`() {
        val doc = """
            ---
            name: minimal
            description: Just a description
            ---
            Body.
        """.trimIndent()
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertTrue(manifest.declaredTools.isEmpty())
        assertEquals(null, manifest.version)
    }

    @Test
    fun `ignores an unknown frontmatter key rather than failing`() {
        val doc = """
            ---
            name: future
            description: Uses a key this build does not know
            author: someone
            ---
            Body.
        """.trimIndent()
        assertIs<SkillParseResult.Success>(parser.parse(doc))
    }

    @Test
    fun `fails when the opening fence is missing`() {
        val result = assertIs<SkillParseResult.Failure>(parser.parse("name: x\ndescription: y\n"))
        assertTrue(result.reason.contains("must start with a '---' fence"), result.reason)
    }

    @Test
    fun `fails when the closing fence is missing`() {
        val doc = "---\nname: x\ndescription: y\n"
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("unterminated frontmatter"), result.reason)
    }

    @Test
    fun `fails when a required key is missing`() {
        val doc = "---\nname: x\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("missing required key 'description'"), result.reason)
    }

    @Test
    fun `fails on a duplicate recognised key`() {
        val doc = "---\nname: x\nname: y\ndescription: d\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("duplicate frontmatter key 'name'"), result.reason)
    }

    @Test
    fun `fails on a malformed frontmatter line`() {
        val doc = "---\nname: x\ndescription d\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("malformed frontmatter line"), result.reason)
    }

    @Test
    fun `fails when the name is not a valid slug`() {
        val doc = "---\nname: not a slug!\ndescription: d\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("must be a slug"), result.reason)
    }
}
