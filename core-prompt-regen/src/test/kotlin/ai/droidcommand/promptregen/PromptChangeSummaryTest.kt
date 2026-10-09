package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptChangeSummaryTest {
    private val original = "Add a retry guard to RefusalDiagnostics.kt. Do not change the public API. It must stay under 100 lines."

    @Test
    fun `requirements kept verbatim are preserved and scaffolding is reported as added`() {
        val regenerated = """
            # Objective
            Add a retry guard to RefusalDiagnostics.kt.
            Do not change the public API.
            It must stay under 100 lines.
            # Acceptance criteria
            - Unit tests pass
        """.trimIndent()
        val s = PromptChangeSummarizer.summarize(original, regenerated)
        assertEquals(3, s.preserved.size)
        assertTrue(s.noDetectedLoss)
        assertTrue(s.added.any { it.contains("Acceptance criteria") })
        assertTrue(s.added.none { it.contains("retry guard") })
    }

    @Test
    fun `a dropped constraint is flagged for review`() {
        val s = PromptChangeSummarizer.summarize(original, "Add a retry guard to RefusalDiagnostics.kt.\nKeep it simple.")
        assertTrue(s.flagged.any { "Do not change the public API" in it.requirement })
        assertTrue(s.flagged.any { "100 lines" in it.requirement })
        assertTrue(!s.noDetectedLoss)
    }

    @Test
    fun `a reworded requirement is reported as clarified, not lost`() {
        val s = PromptChangeSummarizer.summarize(
            "You must not change the public API of the module.",
            "Constraint: you must not change the public API of the module, including signatures.",
        )
        val r = s.requirements.single()
        assertEquals(RequirementStatus.PRESERVED, r.status)

        val reworded = PromptChangeSummarizer.summarize(
            "You must not change the public API of the module.",
            "Constraint: you must never change the public API of this module.",
        )
        assertEquals(RequirementStatus.CLARIFIED, reworded.requirements.single().status)
    }

    @Test
    fun `plain non-requirement sentences are not treated as requirements`() {
        val s = PromptChangeSummarizer.summarize("Hello there. Thanks so much.", "Task: say hi")
        assertTrue(s.requirements.isEmpty())
    }

    @Test
    fun `works end to end against the real regenerator and surfaces loss, if any, instead of hiding it`() {
        val input = "Add caching to core-config/CacheLoader.kt without changing the public API"
        val out = PromptRegenerator.regenerate(RegenerationInput(rawInput = input))
        val s = PromptChangeSummarizer.summarize(input, out.optimizedPrompt)
        assertTrue(s.requirements.isNotEmpty())
        // Whatever the regenerator did, every requirement has an explicit status; none is silently absent.
        assertEquals(s.requirements.size, s.preserved.size + s.clarified.size + s.flagged.size)
    }
}
