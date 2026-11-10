package ai.droidcommand.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillParameterBinderTest {
    private val binder = SkillParameterBinder()

    private fun manifest(
        parameters: List<SkillParameter>,
        instructions: String,
    ) = SkillManifest(
        name = "s",
        description = "A skill",
        instructions = instructions,
        parameters = parameters,
    )

    @Test
    fun `substitutes supplied values into the body`() {
        val m = manifest(
            listOf(SkillParameter("lang", required = true)),
            "Translate into {{lang}}.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { if (it == "lang") "French" else null })
        assertEquals("Translate into French.", result.instructions)
    }

    @Test
    fun `falls back to a default when no value is supplied`() {
        val m = manifest(
            listOf(SkillParameter("tone", default = "neutral")),
            "Use a {{tone}} tone.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { null })
        assertEquals("Use a neutral tone.", result.instructions)
    }

    @Test
    fun `a supplied value overrides the default`() {
        val m = manifest(
            listOf(SkillParameter("tone", default = "neutral")),
            "Use a {{tone}} tone.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { "formal" })
        assertEquals("Use a formal tone.", result.instructions)
    }

    @Test
    fun `a required parameter with no value and no default is reported missing`() {
        val m = manifest(
            listOf(
                SkillParameter("lang", required = true),
                SkillParameter("tone", default = "neutral"),
                SkillParameter("city", required = true),
            ),
            "{{lang}} {{tone}} {{city}}",
        )
        val result = assertIs<SkillBindResult.MissingRequired>(binder.bind(m) { null })
        // Declaration order, only the unsatisfied required ones.
        assertEquals(listOf("lang", "city"), result.missing)
    }

    @Test
    fun `an optional parameter with no value and no default resolves to empty`() {
        val m = manifest(
            listOf(SkillParameter("suffix")),
            "Body{{suffix}}.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { null })
        assertEquals("Body.", result.instructions)
    }

    @Test
    fun `an unknown placeholder is left untouched`() {
        val m = manifest(
            listOf(SkillParameter("known", default = "X")),
            "{{known}} and {{unknown}} stay distinct.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { null })
        assertEquals("X and {{unknown}} stay distinct.", result.instructions)
    }

    @Test
    fun `tolerates inner whitespace in a placeholder`() {
        val m = manifest(
            listOf(SkillParameter("name", default = "Sam")),
            "Hello {{ name }}.",
        )
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { null })
        assertEquals("Hello Sam.", result.instructions)
    }

    @Test
    fun `substitution is single-pass so an injected value is not re-expanded`() {
        val m = manifest(
            listOf(
                SkillParameter("a"),
                SkillParameter("b", default = "SHOULD-NOT-APPEAR"),
            ),
            "{{a}} then {{b}}",
        )
        // a's value literally contains b's placeholder; it must survive verbatim, not be re-expanded.
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { if (it == "a") "{{b}}" else null })
        assertEquals("{{b}} then SHOULD-NOT-APPEAR", result.instructions)
    }

    @Test
    fun `a manifest with no parameters returns the body unchanged`() {
        val m = manifest(emptyList(), "Unchanged body with {{literal}} braces.")
        val result = assertIs<SkillBindResult.Bound>(binder.bind(m) { "ignored" })
        assertEquals("Unchanged body with {{literal}} braces.", result.instructions)
    }
}
