package ai.droidcommand.promptregen

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role

/**
 * Deterministic, keyword/shape-based intent recovery — no LLM call (see module-level rationale in
 * [PromptRegenerator]'s doc comment). Never claims a confidence score it can't back with real
 * evidence; instead exposes [IntentAnalysis.reasoning] (what was actually matched) and
 * [IntentAnalysis.alternatives] (other categories a human could reasonably pick instead), so a
 * caller can decide whether the top pick is confident enough to act on or ambiguous enough to ask
 * about — the spec's own "if multiple plausible interpretations exist, ask a targeted question"
 * rule, made possible by exposing the alternatives rather than silently picking one.
 */
object IntentAnalyzer {
    private val trivialInputs = setOf("?", "go", "continue", "next", "yes", "ok", "okay", "sure", "do it")
    private val errorKeywords = listOf("error", "exception", "fail", "crash", "broken", "bug", "traceback", "stack trace")
    private val implementKeywords = listOf("implement", "add", "build", "create", "write a", "make a")
    private val fixKeywords = listOf("fix", "bug", "broken", "crash", "not working", "doesn't work")
    private val improvePromptKeywords = listOf("prompt", "regenerate", "rewrite", "improve")
    private val stackTraceMarkers = listOf("Exception", "Traceback", "\tat ")

    fun analyze(input: RegenerationInput, repoContext: RepoContext?): IntentAnalysis {
        val text = input.rawInput.trim()
        val normalized = text.lowercase()
        val meaningfulWordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }

        if (input.previousAiResponse != null && FailureClassifier.classify(input.previousAiResponse) != null) {
            return IntentAnalysis(
                category = IntentCategory.RESOLVE_BLOCKER,
                alternatives = emptyList(),
                reasoning = "A previous AI response was classified as a failure; the dominant task now is " +
                    "recovering from that failure, regardless of the raw input's own wording.",
                referencedSubject = null,
            )
        }

        val isTrivial = normalized in trivialInputs || (meaningfulWordCount <= 3 && !normalized.endsWith("?"))
        if (isTrivial) {
            return analyzeTrivialInput(text, input.conversationHistory)
        }

        if (stackTraceMarkers.any { text.contains(it) }) {
            return IntentAnalysis(
                category = IntentCategory.EXPLAIN_ERROR,
                alternatives = listOf(IntentCategory.FIX_BUG),
                reasoning = "Input contains stack-trace-shaped text (an exception name or a 'Traceback'/'at ...' frame).",
                referencedSubject = null,
            )
        }

        if (fixKeywords.any { normalized.contains(it) }) {
            return IntentAnalysis(
                category = IntentCategory.FIX_BUG,
                alternatives = listOf(IntentCategory.EXPLAIN_ERROR),
                reasoning = "Input contains bug-fix language (${fixKeywords.first { normalized.contains(it) }}).",
                referencedSubject = null,
            )
        }

        if (improvePromptKeywords.count { normalized.contains(it) } >= 2) {
            return IntentAnalysis(
                category = IntentCategory.IMPROVE_PROMPT,
                alternatives = emptyList(),
                reasoning = "Input references both a prompt and a rewrite/regenerate/improve action.",
                referencedSubject = null,
            )
        }

        if (implementKeywords.any { normalized.contains(it) }) {
            return IntentAnalysis(
                category = IntentCategory.IMPLEMENT_FEATURE,
                alternatives = emptyList(),
                reasoning = "Input contains feature-implementation language (${implementKeywords.first { normalized.contains(it) }}).",
                referencedSubject = null,
            )
        }

        if (normalized.endsWith("?") || normalized.startsWith("what") || normalized.startsWith("how") ||
            normalized.startsWith("why") || normalized.startsWith("is ") || normalized.startsWith("does ")
        ) {
            return IntentAnalysis(
                category = IntentCategory.ANSWER_QUESTION,
                alternatives = emptyList(),
                reasoning = "Input is phrased as a direct question.",
                referencedSubject = null,
            )
        }

        return IntentAnalysis(
            category = IntentCategory.AMBIGUOUS,
            alternatives = listOf(IntentCategory.IMPLEMENT_FEATURE, IntentCategory.ANSWER_QUESTION),
            reasoning = "Input doesn't match any recognized implement/fix/explain/question shape, and is too " +
                "long to treat as a trivial continuation.",
            referencedSubject = null,
        )
    }

    private fun analyzeTrivialInput(text: String, history: List<Message>): IntentAnalysis {
        val lastSubstantive = history.lastOrNull { it.role == Role.USER && it.content.trim().split(Regex("\\s+")).size > 3 }
            ?: history.lastOrNull { it.content.trim().split(Regex("\\s+")).size > 3 }

        if (lastSubstantive == null) {
            return IntentAnalysis(
                category = IntentCategory.AMBIGUOUS,
                alternatives = emptyList(),
                reasoning = "Input '$text' is too trivial to carry a task on its own, and no prior conversation " +
                    "history exists to recover a subject from.",
                referencedSubject = null,
            )
        }

        val subject = lastSubstantive.content.trim()
        val mentionsError = errorKeywords.any { subject.lowercase().contains(it) }
        val alternatives = mutableListOf(IntentCategory.RESOLVE_BLOCKER)
        if (mentionsError) alternatives.add(IntentCategory.EXPLAIN_ERROR)

        return IntentAnalysis(
            category = IntentCategory.CONTINUE_WORKFLOW,
            alternatives = alternatives,
            reasoning = "Input '$text' is too trivial to carry a task on its own; recovered the likely subject " +
                "from the most recent substantive conversation message instead of treating this as context-free.",
            referencedSubject = subject,
        )
    }
}
