package ai.droidcommand.templates

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.ProviderPreferences
import ai.droidcommand.llm.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplatesTest {
    private fun tpl(body: String) = PromptTemplate("x", "X", TemplateCategory.CUSTOM, body)

    @Test fun `variables are distinct in first-appearance order and tolerate spaces`() {
        assertEquals(listOf("a", "b"), tpl("{{a}} {{ b }} {{a}}").variables)
        assertEquals(emptyList(), tpl("{{1bad}} {x} {{}}").variables)
    }

    @Test fun `expands every occurrence`() {
        val r = TemplateEngine.expand(tpl("Hi {{n}}, bye {{ n }}"), mapOf("n" to "Ann"))
        assertEquals(TemplateResult.Expanded("Hi Ann, bye Ann"), r)
    }

    @Test fun `reports all missing and blank variables`() {
        val r = TemplateEngine.expand(tpl("{{a}} {{b}} {{c}}"), mapOf("a" to "1", "b" to "  "))
        assertEquals(TemplateResult.MissingVariables(listOf("b", "c")), r)
    }

    @Test fun `values are inserted literally and not re-expanded`() {
        val r = TemplateEngine.expand(tpl("{{a}} {{b}}"), mapOf("a" to "{{b}}", "b" to "SECRET"))
        assertEquals(TemplateResult.Expanded("{{b}} SECRET"), r)
    }

    @Test fun `replacement text with dollar and backslash is literal`() {
        val r = TemplateEngine.expand(tpl("{{a}}"), mapOf("a" to "$1 \\n"))
        assertEquals(TemplateResult.Expanded("$1 \\n"), r)
    }

    @Test fun `bundled content has the promised counts and unique ids`() {
        assertEquals(20, BundledContent.templates.size)
        TemplateCategory.entries.filter { it != TemplateCategory.CUSTOM }.forEach { c ->
            assertEquals(5, BundledContent.templates.count { it.category == c }, "$c")
        }
        assertEquals(5, BundledContent.skills.size)
        assertEquals(25, (BundledContent.templates.map { it.id } + BundledContent.skills.map { it.id }).toSet().size)
        assertTrue(BundledContent.templates.all { it.variables.isNotEmpty() })
    }

    @Test fun `seeding is idempotent and preserves user edits`() {
        val ts = InMemoryTemplateStore()
        val ss = InMemorySkillStore()
        assertEquals(25, BundledContent.seed(ts, ss))
        val edited = BundledContent.templates.first().copy(body = "my edit {{x}}")
        ts.save(edited)
        assertEquals(0, BundledContent.seed(ts, ss))
        assertEquals("my edit {{x}}", ts.get(edited.id)?.body)
        ts.save(PromptTemplate("user.1", "Mine", TemplateCategory.CUSTOM, "{{q}}"))
        assertTrue(ts.delete("user.1"))
        assertFalse(ts.delete("user.1"))
    }

    @Test fun `skill prompt goes first and existing system prompt is kept`() {
        val skill = Skill("s", "S", "d", "SKILL")
        val msgs = listOf(Message(Role.USER, "hi"))
        assertEquals("SKILL", SkillApplier.apply(skill, LlmRequest(null, msgs)).systemPrompt)
        assertEquals("SKILL", SkillApplier.apply(skill, LlmRequest("  ", msgs)).systemPrompt)
        assertEquals("SKILL\n\nBASE", SkillApplier.apply(skill, LlmRequest("BASE", msgs)).systemPrompt)
    }

    @Test fun `local preference requires local and never relaxes an existing requirement`() {
        val local = Skill("s", "S", "d", "p", preferredProviderType = ProviderType.LOCAL)
        assertTrue(SkillApplier.preferencesFor(local).requireLocal)
        val cloud = Skill("c", "C", "d", "p", preferredProviderType = ProviderType.CLOUD)
        assertFalse(SkillApplier.preferencesFor(cloud).requireLocal)
        assertTrue(SkillApplier.preferencesFor(cloud, ProviderPreferences(requireLocal = true)).requireLocal)
    }

    @Test fun `blank fields are rejected`() {
        assertFailsWith<IllegalArgumentException> { Skill("s", "S", "d", " ") }
        assertFailsWith<IllegalArgumentException> { PromptTemplate("", "n", TemplateCategory.CUSTOM, "b") }
    }
}
