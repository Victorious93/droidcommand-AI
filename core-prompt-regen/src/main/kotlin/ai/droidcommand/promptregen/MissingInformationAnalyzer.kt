package ai.droidcommand.promptregen

/**
 * Names what's genuinely missing for a given [IntentAnalysis], rather than either fabricating
 * detail that was never given or (the spec's explicit anti-pattern) just asking the user to restate
 * the whole task. Each rule only fires when the raw input actually lacks the relevant signal — a
 * request that already names a file/module/expected-behavior doesn't get a spurious "missing" entry.
 */
object MissingInformationAnalyzer {
    private val fileOrModuleMention = Regex("""[\w./-]+\.\w{1,5}\b|\bmodule\b|\bfile\b|\bclass\b|\bfunction\b""", RegexOption.IGNORE_CASE)
    private val expectedBehaviorMention = Regex("""\bshould\b|\bexpected\b|\binstead\b|\brather than\b""", RegexOption.IGNORE_CASE)

    fun analyze(input: RegenerationInput, intent: IntentAnalysis, repoContext: RepoContext?): List<String> {
        val missing = mutableListOf<String>()
        val text = input.rawInput

        when (intent.category) {
            IntentCategory.IMPLEMENT_FEATURE -> {
                if (!fileOrModuleMention.containsMatchIn(text)) {
                    missing.add("Which file(s) or module this should live in is not stated.")
                }
                if (!expectedBehaviorMention.containsMatchIn(text)) {
                    missing.add("The intended behavior/acceptance criteria for the new feature are not described.")
                }
            }
            IntentCategory.FIX_BUG -> {
                if (!expectedBehaviorMention.containsMatchIn(text)) {
                    missing.add("Expected vs. actual behavior is not described.")
                }
                if (!fileOrModuleMention.containsMatchIn(text)) {
                    missing.add("Which file(s) or module the bug is in is not stated.")
                }
            }
            IntentCategory.EXPLAIN_ERROR -> {
                if (!text.contains("Exception") && !text.contains("Traceback")) {
                    missing.add("No exception type or stack trace was included alongside the error description.")
                }
            }
            IntentCategory.CONTINUE_WORKFLOW -> {
                if (intent.referencedSubject == null) {
                    missing.add("No prior conversation context exists to determine what workflow to continue.")
                }
            }
            IntentCategory.AMBIGUOUS -> {
                missing.add("The task itself: no prior context establishes one, and the raw input doesn't state it.")
            }
            IntentCategory.RESOLVE_BLOCKER, IntentCategory.IMPROVE_PROMPT, IntentCategory.ANSWER_QUESTION -> {
                // No additional missing-information rules for these categories in this first slice.
            }
        }

        if (repoContext == null) {
            missing.add("No repository path was provided, so repo-aware context recovery (CLAUDE.md/AGENTS.md/docs) was skipped.")
        }

        return missing
    }
}
