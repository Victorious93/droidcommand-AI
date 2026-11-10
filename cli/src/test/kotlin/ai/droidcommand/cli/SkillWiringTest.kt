package ai.droidcommand.cli

import ai.droidcommand.config.ConfigSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillWiringTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("cli-skill-wiring-test")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun config(values: Map<String, String?>): ConfigSource = ConfigSource { key -> values[key] }

    private fun writeSkill(name: String, body: String) {
        val dir = (root / name).also { it.createDirectories() }
        (dir / "SKILL.md").writeText(
            """
            ---
            name: $name
            description: Skill $name
            ---
            $body
            """.trimIndent(),
        )
    }

    /** Writes a SKILL.md whose frontmatter declares a `parameters:` block (each entry is one '- ' line's content). */
    private fun writeSkillWithParameters(name: String, parameters: List<String>, body: String) {
        val dir = (root / name).also { it.createDirectories() }
        val block = parameters.joinToString("\n") { "  - $it" }
        (dir / "SKILL.md").writeText(
            "---\nname: $name\ndescription: Skill $name\nparameters:\n$block\n---\n$body\n",
        )
    }

    @Test
    fun `returns null when no skills dir is configured`() {
        assertNull(buildSkillSystemPrompt(config(emptyMap())))
        assertNull(buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to "  "))))
    }

    @Test
    fun `returns null when skills exist on disk but none are enabled`() {
        writeSkill("summarize", "Summarize the document.")
        // No enabled-skills.txt -> everything stays disabled -> no contribution.
        assertNull(buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString()))))
    }

    @Test
    fun `assembles only the skills enabled in the state file`() {
        writeSkill("summarize", "Summarize the document.")
        writeSkill("translate", "Translate the text.")
        (root / "enabled-skills.txt").writeText("summarize=true\ntranslate=false\n")

        val prompt = buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString())))!!
        assertTrue(prompt.contains("## summarize: Skill summarize"))
        assertTrue(prompt.contains("Summarize the document."))
        assertFalse(prompt.contains("translate"), "a skill marked disabled must not contribute")
    }

    @Test
    fun `honours a custom state file path`() {
        writeSkill("summarize", "Summarize the document.")
        val stateFile = root / "custom-state.txt"
        stateFile.writeText("summarize=true\n")

        val prompt = buildSkillSystemPrompt(
            config(
                mapOf(
                    "DROIDCOMMAND_SKILLS_DIR" to root.toString(),
                    "DROIDCOMMAND_SKILLS_STATE_FILE" to stateFile.toString(),
                ),
            ),
        )!!
        assertTrue(prompt.contains("## summarize: Skill summarize"))
    }

    @Test
    fun `a malformed skill is skipped without breaking discovery of valid ones`() {
        writeSkill("good", "Good body.")
        (root / "bad").also { it.createDirectories() }
        (root / "bad" / "SKILL.md").writeText("---\nname: bad\n") // missing description + closing fence
        (root / "enabled-skills.txt").writeText("good=true\nbad=true\n")

        val prompt = buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString())))!!
        assertTrue(prompt.contains("## good: Skill good"))
        assertFalse(prompt.contains("name: bad"))
    }

    @Test
    fun `substitutes a configured parameter value into the prompt`() {
        writeSkillWithParameters("translate", listOf("target | Target language | required"), "Translate into {{target}}.")
        (root / "enabled-skills.txt").writeText("translate=true\n")

        val prompt = buildSkillSystemPrompt(
            config(
                mapOf(
                    "DROIDCOMMAND_SKILLS_DIR" to root.toString(),
                    "DROIDCOMMAND_SKILL_TRANSLATE_TARGET" to "French",
                ),
            ),
        )!!
        assertTrue(prompt.contains("Translate into French."), prompt)
        assertFalse(prompt.contains("{{target}}"), "the placeholder must be filled")
    }

    @Test
    fun `falls back to a parameter default when the value is unset`() {
        writeSkillWithParameters("translate", listOf("tone | Desired tone | optional | neutral"), "Use a {{tone}} tone.")
        (root / "enabled-skills.txt").writeText("translate=true\n")

        val prompt = buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString())))!!
        assertTrue(prompt.contains("Use a neutral tone."), prompt)
    }

    @Test
    fun `skips an enabled skill whose required parameter is unset, keeping others`() {
        writeSkillWithParameters("translate", listOf("target | Target language | required"), "Translate into {{target}}.")
        writeSkill("summarize", "Summarize the document.")
        (root / "enabled-skills.txt").writeText("translate=true\nsummarize=true\n")

        // No DROIDCOMMAND_SKILL_TRANSLATE_TARGET -> translate is skipped; summarize still contributes.
        val prompt = buildSkillSystemPrompt(config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString())))!!
        assertTrue(prompt.contains("## summarize: Skill summarize"), prompt)
        assertFalse(prompt.contains("translate"), "a skill missing a required parameter must not contribute")
    }

    @Test
    fun `reads parameter values under the normalized config key`() {
        // A hyphenated skill and parameter name map to an underscored, upper-cased env key.
        writeSkillWithParameters(
            "web-search",
            listOf("max-results | How many results | required"),
            "Fetch {{max-results}} results.",
        )
        (root / "enabled-skills.txt").writeText("web-search=true\n")

        val prompt = buildSkillSystemPrompt(
            config(
                mapOf(
                    "DROIDCOMMAND_SKILLS_DIR" to root.toString(),
                    "DROIDCOMMAND_SKILL_WEB_SEARCH_MAX_RESULTS" to "5",
                ),
            ),
        )!!
        assertTrue(prompt.contains("Fetch 5 results."), prompt)
    }

    @Test
    fun `builds the parameter config key by upper-casing and underscoring`() {
        assertEquals("DROIDCOMMAND_SKILL_TRANSLATE_TARGET", skillParameterConfigKey("translate", "target"))
        assertEquals("DROIDCOMMAND_SKILL_WEB_SEARCH_MAX_RESULTS", skillParameterConfigKey("web-search", "max-results"))
        assertEquals("DROIDCOMMAND_SKILL_A_B_C_D", skillParameterConfigKey("a.b", "c.d"))
    }
}
