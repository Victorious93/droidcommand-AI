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

    @Test
    fun `defaults parameters to empty when absent`() {
        val doc = "---\nname: x\ndescription: A skill\n---\nBody."
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals(emptyList(), manifest.parameters)
    }

    @Test
    fun `parses a block of parameters with all fields`() {
        val doc = """
            ---
            name: translate
            description: Translate text
            parameters:
              - target_language | Language to translate into | required
              - tone | Desired tone | optional | neutral
              - city
            ---
            Translate into {{target_language}} with a {{tone}} tone.
        """.trimIndent()
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals(3, manifest.parameters.size)

        val target = manifest.parameters[0]
        assertEquals("target_language", target.name)
        assertEquals("Language to translate into", target.description)
        assertTrue(target.required)
        assertEquals(null, target.default)

        val tone = manifest.parameters[1]
        assertEquals("tone", tone.name)
        assertEquals(false, tone.required)
        assertEquals("neutral", tone.default)

        val city = manifest.parameters[2]
        assertEquals("city", city.name)
        assertEquals("", city.description)
        assertEquals(false, city.required)
        assertEquals(null, city.default)
    }

    @Test
    fun `distinguishes an explicit empty default from no default`() {
        val doc = "---\nname: x\ndescription: d\nparameters:\n  - suffix | A suffix | optional | \n---\nBody {{suffix}}."
        val manifest = assertIs<SkillParseResult.Success>(parser.parse(doc)).manifest
        assertEquals("", manifest.parameters.single().default)
    }

    @Test
    fun `fails on a parameter with an invalid requiredness token`() {
        val doc = "---\nname: x\ndescription: d\nparameters:\n  - p | desc | mandatory\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("must be 'required' or 'optional'"), result.reason)
    }

    @Test
    fun `fails on a parameter with an invalid name`() {
        val doc = "---\nname: x\ndescription: d\nparameters:\n  - not a slug! | desc\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("must be a slug"), result.reason)
    }

    @Test
    fun `fails on duplicate parameter names`() {
        val doc = "---\nname: x\ndescription: d\nparameters:\n  - p | one\n  - p | two\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("duplicate skill parameter 'p'"), result.reason)
    }

    @Test
    fun `fails when parameters is given an inline value instead of a block`() {
        val doc = "---\nname: x\ndescription: d\nparameters: a, b\n---\nBody."
        val result = assertIs<SkillParseResult.Failure>(parser.parse(doc))
        assertTrue(result.reason.contains("must be a block list"), result.reason)
    }
}
