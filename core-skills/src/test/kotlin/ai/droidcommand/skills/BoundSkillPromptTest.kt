package ai.droidcommand.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundSkillPromptTest {
    @Test
    fun `enabled skill with filled parameters reaches the prompt`() {
        val result = assembleBoundSkills(
            listOf(skill("greet", "Say hello {{name}}", enabled = true, parameters = listOf(SkillParameter("name", required = true)))),
        ) { _, _ -> "Sam" }
        assertNotNull(result.prompt)
        assertTrue(result.prompt!!.contains("Say hello Sam"))
        assertTrue(result.skipped.isEmpty())
        assertNull(result.skippedNotice())
    }

    @Test
    fun `missing required parameters skip that skill and do not crash`() {
        val result = assembleBoundSkills(
            listOf(
                skill("greet", "Say hello {{name}}", enabled = true, parameters = listOf(SkillParameter("name", required = true))),
                skill("ping", "Just ping.", enabled = true),
            ),
        ) { _, _ -> null }
        assertNotNull(result.prompt)
        assertTrue(result.prompt!!.contains("Just ping."))
        assertFalse(result.prompt!!.contains("Say hello"))
        assertEquals(listOf(SkippedSkill("greet", listOf("name"))), result.skipped)
        assertEquals(
            "Skill 'greet' was skipped: missing required settings (name).",
            result.skippedNotice(),
        )
    }

    @Test
    fun `disabled skills are not reported as skipped even if parameters are missing`() {
        val result = assembleBoundSkills(
            listOf(skill("greet", "Say hello {{name}}", enabled = false, parameters = listOf(SkillParameter("name", required = true)))),
        ) { _, _ -> null }
        assertNull(result.prompt)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `defaults satisfy a required parameter`() {
        val result = assembleBoundSkills(
            listOf(skill("greet", "Hello {{name}}", enabled = true, parameters = listOf(SkillParameter("name", required = true, default = "Ada")))),
        ) { _, _ -> null }
        assertTrue(result.prompt!!.contains("Hello Ada"))
        assertTrue(result.skipped.isEmpty())
    }

    private fun skill(
        name: String,
        instructions: String,
        enabled: Boolean,
        parameters: List<SkillParameter> = emptyList(),
    ) = InstalledSkill(
        manifest = SkillManifest(name = name, description = name, instructions = instructions, parameters = parameters),
        enabled = enabled,
    )
}
