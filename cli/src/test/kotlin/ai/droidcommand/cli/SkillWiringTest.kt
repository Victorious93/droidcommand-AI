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
}
