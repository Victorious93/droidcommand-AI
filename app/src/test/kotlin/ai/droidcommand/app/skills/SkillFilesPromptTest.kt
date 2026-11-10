package ai.droidcommand.app.skills

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillFilesPromptTest {
    @Test
    fun `enabled skill with saved values is assembled into the chat prompt`() {
        val root = Files.createTempDirectory("skill-files-prompt")
        val skillDir = SkillFilesLocations.skillsDir(root).resolve("greet")
        Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: greet
            description: Greet someone
            parameters:
              - name | Who to greet | required
            ---
            Hello {{name}}.
            """.trimIndent(),
        )
        SkillFilesLocations.stateFile(root).writeText("greet=true\n")
        SkillParameterValues.save(
            SkillFilesLocations.valuesFile(root),
            mapOf("greet" to mapOf("name" to "Sam")),
        )

        val result = SkillFilesPrompt.assemble(root)
        assertNotNull(result.prompt)
        assertTrue(result.prompt!!.contains("Hello Sam."))
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `missing required values skip the skill and return a visible note`() {
        val root = Files.createTempDirectory("skill-files-prompt-skip")
        val skillDir = SkillFilesLocations.skillsDir(root).resolve("greet")
        Files.createDirectories(skillDir)
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: greet
            description: Greet someone
            parameters:
              - name | Who to greet | required
            ---
            Hello {{name}}.
            """.trimIndent(),
        )
        SkillFilesLocations.stateFile(root).writeText("greet=true\n")

        val result = SkillFilesPrompt.assemble(root)
        assertNull(result.prompt)
        assertEquals(listOf("name"), result.skipped.single().missing)
        assertTrue(result.skippedNotice()!!.contains("greet"))
        assertTrue(result.skippedNotice()!!.contains("name"))
    }

    @Test
    fun `a missing skills folder does not crash`() {
        val root = Files.createTempDirectory("skill-files-prompt-empty")
        val result = SkillFilesPrompt.assemble(root)
        assertNull(result.prompt)
        assertTrue(result.skipped.isEmpty())
    }
}
