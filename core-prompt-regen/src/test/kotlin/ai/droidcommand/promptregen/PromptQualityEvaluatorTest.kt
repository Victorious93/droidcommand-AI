package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptQualityEvaluatorTest {
    private val strong = """
        Implement a retry guard in core-prompt-regen/RefusalDiagnostics.kt.
        - Must not resend an unchanged prompt after a refusal.
        - Add unit tests and run ./gradlew test; all tests must pass.
        Acceptance: RetryGuard.check returns Blocked after 3 attempts.
    """.trimIndent()

    private fun dim(r: PromptQualityReport, d: QualityDimension) = r.dimensions.single { it.dimension == d }

    @Test
    fun `a specific structured prompt outscores a vague one overall and on specificity`() {
        val good = PromptQualityEvaluator.evaluate(strong, TargetModel.CLAUDE_CODE)
        val bad = PromptQualityEvaluator.evaluate("make it better somehow, fix stuff", TargetModel.CLAUDE_CODE)
        assertTrue(good.overall > bad.overall + 20, "good=${good.overall} bad=${bad.overall}")
        assertTrue(dim(good, QualityDimension.TECHNICAL_SPECIFICITY).score > dim(bad, QualityDimension.TECHNICAL_SPECIFICITY).score)
        assertTrue(dim(good, QualityDimension.TESTABILITY).score > dim(bad, QualityDimension.TESTABILITY).score)
    }

    @Test
    fun `every dimension is present, bounded and explained`() {
        val r = PromptQualityEvaluator.evaluate(strong)
        assertEquals(QualityDimension.entries.toSet(), r.dimensions.map { it.dimension }.toSet())
        r.dimensions.forEach {
            assertTrue(it.score in 0..100)
            assertTrue(it.reasons.isNotEmpty())
        }
        assertTrue("Not a verified prediction" in r.disclaimer)
    }

    @Test
    fun `contradictory instructions lower internal consistency with a named reason`() {
        val r = PromptQualityEvaluator.evaluate("Be brief but also give an exhaustive, detailed analysis of the module.")
        val c = dim(r, QualityDimension.INTERNAL_CONSISTENCY)
        assertTrue(c.reasons.any { "possible conflict" in it })
        assertTrue(c.score < dim(PromptQualityEvaluator.evaluate("Give a detailed analysis of the module."), QualityDimension.INTERNAL_CONSISTENCY).score)
    }

    @Test
    fun `repetition lowers context efficiency`() {
        val repeated = "Fix the login bug in the app. ".repeat(6)
        val e = dim(PromptQualityEvaluator.evaluate(repeated), QualityDimension.CONTEXT_EFFICIENCY)
        assertTrue(e.reasons.any { "repet" in it })
        assertTrue(e.score < dim(PromptQualityEvaluator.evaluate(strong), QualityDimension.CONTEXT_EFFICIENCY).score)
    }

    @Test
    fun `a very large prompt is flagged for a small local model but not for Claude`() {
        val big = "Refactor module ${"alpha beta gamma ".repeat(600)}"
        val local = dim(PromptQualityEvaluator.evaluate(big, TargetModel.LOCAL_MODEL), QualityDimension.PROVIDER_COMPATIBILITY)
        val claude = dim(PromptQualityEvaluator.evaluate(big, TargetModel.CLAUDE_CODE), QualityDimension.PROVIDER_COMPATIBILITY)
        assertTrue(local.reasons.any { "large for a small local model" in it })
        assertTrue(local.score < claude.score)
    }

    @Test
    fun `empty prompt scores zero clarity and never throws`() {
        val r = PromptQualityEvaluator.evaluate("   ")
        assertEquals(0, dim(r, QualityDimension.CLARITY).score)
        assertEquals(0, r.estimatedTokens)
    }

    @Test
    fun `evaluation is deterministic`() {
        assertEquals(PromptQualityEvaluator.evaluate(strong, TargetModel.CODEX), PromptQualityEvaluator.evaluate(strong, TargetModel.CODEX))
    }
}
