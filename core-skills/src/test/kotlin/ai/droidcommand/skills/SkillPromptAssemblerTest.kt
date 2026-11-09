package ai.droidcommand.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillPromptAssemblerTest {
    private val assembler = SkillPromptAssembler()

    private fun installed(name: String, description: String, instructions: String, enabled: Boolean) =
        InstalledSkill(
            manifest = SkillManifest(name = name, description = description, instructions = instructions),
            enabled = enabled,
        )

    @Test
    fun `returns null when no skill is enabled`() {
        assertNull(assembler.assemble(emptyList()))
        assertNull(
            assembler.assemble(
                listOf(installed("a", "d", "body", enabled = false)),
            ),
        )
    }

    @Test
    fun `includes only enabled skills, each as its own section`() {
        val prompt = assembler.assemble(
            listOf(
                installed("summarize", "Summarize a document", "Produce five bullets.", enabled = true),
                installed("secret", "Should not appear", "Disabled body.", enabled = false),
            ),
        )!!
        assertTrue(prompt.contains("## summarize: Summarize a document"))
        assertTrue(prompt.contains("Produce five bullets."))
        assertFalse(prompt.contains("secret"), "a disabled skill must not reach the prompt")
        assertFalse(prompt.contains("Disabled body."))
    }

    @Test
    fun `orders skills by name for determinism`() {
        val prompt = assembler.assemble(
            listOf(
                installed("zebra", "z", "", enabled = true),
                installed("alpha", "a", "", enabled = true),
                installed("mango", "m", "", enabled = true),
            ),
        )!!
        val order = listOf("## alpha", "## mango", "## zebra").map { prompt.indexOf(it) }
        assertEquals(order.sorted(), order, "sections should appear in name order")
        assertTrue(order.all { it >= 0 })
    }

    @Test
    fun `a skill with blank instructions contributes only its header line`() {
        val prompt = assembler.assemble(
            listOf(installed("ping", "Just a description", "   ", enabled = true)),
        )!!
        assertTrue(prompt.contains("## ping: Just a description"))
        // No stray body line for the blank instructions.
        assertFalse(prompt.contains("## ping: Just a description\n\n"))
    }

    @Test
    fun `the block opens with the non-granting framing header`() {
        val prompt = assembler.assemble(listOf(installed("s", "d", "b", enabled = true)))!!
        assertTrue(prompt.startsWith(SkillPromptAssembler.DEFAULT_HEADER))
        assertTrue(prompt.contains("does not grant you any tool"))
        assertTrue(prompt.contains("does not override the user's request or your safety rules"))
    }

    @Test
    fun `a custom header is honoured`() {
        val prompt = SkillPromptAssembler(header = "CUSTOM HEADER")
            .assemble(listOf(installed("s", "d", "b", enabled = true)))!!
        assertTrue(prompt.startsWith("CUSTOM HEADER"))
    }

    @Test
    fun `assembles straight from a registry's enabled skills`() {
        val registry = SkillRegistry()
        registry.install(SkillManifest(name = "a", description = "da", instructions = "Abody"))
        registry.install(SkillManifest(name = "b", description = "db", instructions = "Bbody"))
        registry.enable("b") // only b is enabled

        val prompt = assembler.assemble(registry.list())!!
        assertTrue(prompt.contains("## b: db"))
        assertTrue(prompt.contains("Bbody"))
        assertFalse(prompt.contains("## a: da"), "the un-enabled skill must be excluded")
    }
}
