package ai.droidcommand.promptregen

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentAnalyzerTest {
    @Test
    fun `bare question mark with prior feature discussion continues that workflow`() {
        val history = listOf(
            Message(Role.USER, "Add a built-in Prompt Regenerator capable of transforming a user's natural-language request into an optimized prompt."),
        )
        val input = RegenerationInput(rawInput = "?", conversationHistory = history)

        val analysis = IntentAnalyzer.analyze(input, repoContext = null)

        assertEquals(IntentCategory.CONTINUE_WORKFLOW, analysis.category)
        assertTrue(analysis.referencedSubject!!.contains("Prompt Regenerator"))
    }

    @Test
    fun `bare question mark with no history at all is honestly ambiguous`() {
        val input = RegenerationInput(rawInput = "?", conversationHistory = emptyList())

        val analysis = IntentAnalyzer.analyze(input, repoContext = null)

        assertEquals(IntentCategory.AMBIGUOUS, analysis.category)
        assertNull(analysis.referencedSubject)
    }

    @Test
    fun `trivial input after an error-mentioning message offers explain-error as an alternative`() {
        val history = listOf(Message(Role.USER, "The build keeps crashing with a NullPointerException in the shell tool"))
        val input = RegenerationInput(rawInput = "continue", conversationHistory = history)

        val analysis = IntentAnalyzer.analyze(input, repoContext = null)

        assertEquals(IntentCategory.CONTINUE_WORKFLOW, analysis.category)
        assertTrue(IntentCategory.EXPLAIN_ERROR in analysis.alternatives)
    }

    @Test
    fun `implement keywords select implement feature`() {
        val input = RegenerationInput(rawInput = "Please implement a new caching layer for the config loader")
        assertEquals(IntentCategory.IMPLEMENT_FEATURE, IntentAnalyzer.analyze(input, null).category)
    }

    @Test
    fun `fix keywords select fix bug`() {
        val input = RegenerationInput(rawInput = "The login button is broken and doesn't work on Android 14")
        assertEquals(IntentCategory.FIX_BUG, IntentAnalyzer.analyze(input, null).category)
    }

    @Test
    fun `stack trace shaped input selects explain error`() {
        val input = RegenerationInput(
            rawInput = "java.lang.NullPointerException: value\n\tat ai.droidcommand.agent.ToolExecutor.run",
        )
        assertEquals(IntentCategory.EXPLAIN_ERROR, IntentAnalyzer.analyze(input, null).category)
    }

    @Test
    fun `question shaped input selects answer question`() {
        val input = RegenerationInput(rawInput = "What does the SecureToolExecutor's grantId parameter actually do?")
        assertEquals(IntentCategory.ANSWER_QUESTION, IntentAnalyzer.analyze(input, null).category)
    }

    @Test
    fun `prompt and rewrite together select improve prompt`() {
        val input = RegenerationInput(rawInput = "Can you rewrite and improve this prompt so it is more specific")
        assertEquals(IntentCategory.IMPROVE_PROMPT, IntentAnalyzer.analyze(input, null).category)
    }

    @Test
    fun `unrecognized longer input is ambiguous with alternatives offered`() {
        val input = RegenerationInput(rawInput = "The purple elephant discusses quarterly synergy over lunch meetings")
        val analysis = IntentAnalyzer.analyze(input, null)
        assertEquals(IntentCategory.AMBIGUOUS, analysis.category)
        assertTrue(analysis.alternatives.isNotEmpty())
    }

    @Test
    fun `a classified failed previous response forces resolve blocker regardless of wording`() {
        val input = RegenerationInput(
            rawInput = "ok",
            previousAiResponse = "I don't have enough information to proceed.",
        )
        val analysis = IntentAnalyzer.analyze(input, null)
        assertEquals(IntentCategory.RESOLVE_BLOCKER, analysis.category)
    }
}
