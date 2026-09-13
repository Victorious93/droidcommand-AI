package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptComposerTest {
    private val baseIntent = IntentAnalysis(IntentCategory.IMPLEMENT_FEATURE, emptyList(), "reasoning", null)

    @Test
    fun `every target model output includes the core anti-hallucination clauses`() {
        for (target in TargetModel.entries) {
            val input = RegenerationInput(rawInput = "Add a caching layer", targetModel = target)
            val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

            assertTrue(result.optimizedPrompt.contains("Never invent files, APIs, commands"), "missing for $target")
            assertTrue(result.optimizedPrompt.contains("Never claim tests passed unless you actually executed them"), "missing for $target")
            assertTrue(result.optimizedPrompt.contains("Verify the final implementation"), "missing for $target")
        }
    }

    @Test
    fun `no output ever contains a fabricated ROADMAP or CAP identifier`() {
        for (target in TargetModel.entries) {
            val input = RegenerationInput(rawInput = "Add a caching layer", targetModel = target)
            val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

            assertFalse(Regex("ROADMAP-\\d").containsMatchIn(result.optimizedPrompt))
            assertFalse(Regex("CAP-\\d").containsMatchIn(result.optimizedPrompt))
        }
    }

    @Test
    fun `claude code target names CLAUDE md and real tool execution`() {
        val input = RegenerationInput(rawInput = "Add a caching layer", targetModel = TargetModel.CLAUDE_CODE)
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

        assertTrue(result.optimizedPrompt.contains("CLAUDE.md"))
        assertTrue(result.optimizedPrompt.contains("real tool execution", ignoreCase = true))
    }

    @Test
    fun `codex target names AGENTS md and minimal scope`() {
        val input = RegenerationInput(rawInput = "Add a caching layer", targetModel = TargetModel.CODEX)
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

        assertTrue(result.optimizedPrompt.contains("AGENTS.md"))
        assertTrue(result.optimizedPrompt.contains("minimal", ignoreCase = true))
    }

    @Test
    fun `repo findings only name markers that were actually found`() {
        val repoContext = RepoContext(hasClaudeMd = true, hasAgentsMd = false, hasReadme = false, docFiles = emptyList(), gitBranch = "main")
        val input = RegenerationInput(rawInput = "Add a caching layer", targetModel = TargetModel.CLAUDE_CODE, repoRoot = null)
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = repoContext, failure = null)

        assertTrue(result.optimizedPrompt.contains("Found in the repository: CLAUDE.md"))
        assertFalse(result.optimizedPrompt.contains("Found in the repository: CLAUDE.md, AGENTS.md"))
    }

    @Test
    fun `no repo context yields an honest assumption entry, not a fabricated repo description`() {
        val input = RegenerationInput(rawInput = "Add a caching layer")
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

        assertTrue(result.assumptions.any { it.contains("No repository path") })
        assertFalse(result.optimizedPrompt.contains("Inspect the repository's project instructions"))
    }

    @Test
    fun `a classified failure prepends a correction section naming the category`() {
        val failure = FailureClassification(FailureCategory.MISSING_CONTEXT, evidence = "i don't have enough information to proceed")
        val input = RegenerationInput(rawInput = "ok", previousAiResponse = "I don't have enough information to proceed.")
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = failure)

        assertTrue(result.optimizedPrompt.contains("MISSING_CONTEXT"))
        assertTrue(result.contextUsed.any { it.contains("Previous AI response") })
    }

    @Test
    fun `default target model is GENERAL when none is specified`() {
        val input = RegenerationInput(rawInput = "Add a caching layer")
        val result = PromptComposer.compose(input, baseIntent, emptyList(), repoContext = null, failure = null)

        assertTrue(result.targetModel == TargetModel.GENERAL)
    }
}
