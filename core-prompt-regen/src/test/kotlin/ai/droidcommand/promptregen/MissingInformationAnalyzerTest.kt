package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingInformationAnalyzerTest {
    @Test
    fun `implement feature with no file and no expected behavior flags both`() {
        val input = RegenerationInput(rawInput = "Add a new caching layer")
        val intent = IntentAnalysis(IntentCategory.IMPLEMENT_FEATURE, emptyList(), "reasoning", null)

        val missing = MissingInformationAnalyzer.analyze(input, intent, repoContext = null)

        assertTrue(missing.any { it.contains("file(s) or module") })
        assertTrue(missing.any { it.contains("acceptance criteria") })
    }

    @Test
    fun `implement feature naming a file and expected behavior flags neither`() {
        val input = RegenerationInput(
            rawInput = "Add a caching layer in core-config/CacheLoader.kt; it should evict entries after 5 minutes",
        )
        val intent = IntentAnalysis(IntentCategory.IMPLEMENT_FEATURE, emptyList(), "reasoning", null)

        val missing = MissingInformationAnalyzer.analyze(input, intent, repoContext = RepoContext(false, false, false, emptyList(), null))

        assertFalse(missing.any { it.contains("file(s) or module") })
        assertFalse(missing.any { it.contains("acceptance criteria") })
    }

    @Test
    fun `ambiguous intent always flags the task itself as missing`() {
        val input = RegenerationInput(rawInput = "?")
        val intent = IntentAnalysis(IntentCategory.AMBIGUOUS, emptyList(), "reasoning", null)

        val missing = MissingInformationAnalyzer.analyze(input, intent, repoContext = null)

        assertTrue(missing.any { it.contains("task itself") })
    }

    @Test
    fun `no repo root flags that repo-aware recovery was skipped`() {
        val input = RegenerationInput(rawInput = "Explain how the login flow works")
        val intent = IntentAnalysis(IntentCategory.ANSWER_QUESTION, emptyList(), "reasoning", null)

        val missing = MissingInformationAnalyzer.analyze(input, intent, repoContext = null)

        assertTrue(missing.any { it.contains("No repository path") })
    }

    @Test
    fun `continue workflow with a recovered subject has no missing-subject entry`() {
        val input = RegenerationInput(rawInput = "?")
        val intent = IntentAnalysis(IntentCategory.CONTINUE_WORKFLOW, emptyList(), "reasoning", "the Prompt Regenerator feature")

        val missing = MissingInformationAnalyzer.analyze(
            input,
            intent,
            repoContext = RepoContext(true, false, true, emptyList(), "main"),
        )

        assertFalse(missing.any { it.contains("No prior conversation context") })
    }
}
