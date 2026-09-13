package ai.droidcommand.promptregen

/**
 * Turns a user's raw, possibly vague or incomplete input into an optimized prompt for an external
 * coding AI, per the pipeline: Input -> Context Discovery -> Intent Detection -> Missing
 * Information Analysis -> Task Construction -> Model Optimization -> Verification Criteria -> Final
 * Prompt ([RepoContextScanner] -> [FailureClassifier] -> [IntentAnalyzer] ->
 * [MissingInformationAnalyzer] -> [PromptComposer]).
 *
 * **Deliberately LLM-free, and this is honest, not a downgrade.** No LLM credentials exist in most
 * environments this project runs in (every existing `docs/AUDIT_2026-09-05.md` addendum states
 * this), and nothing in the core pipeline above actually needs a model call: recovering repo state
 * is a filesystem read, classifying a known failure shape is pattern matching, and building a
 * structured prompt from templates is string composition. A future slice could add an
 * LLM-backed intent classifier as a strictly optional upgrade (composed the same way
 * `core-llm-factory.LlmProviderFactory.createPlanner` composes an LLM into `core-agent`'s
 * LLM-agnostic `Planner` seam) without touching anything in this module — deliberately not built
 * here, named as a real follow-up rather than silently assumed.
 */
object PromptRegenerator {
    fun regenerate(input: RegenerationInput): RegeneratedPrompt {
        val repoContext = input.repoRoot?.let { RepoContextScanner.scan(it) }
        val failure = input.previousAiResponse?.let { FailureClassifier.classify(it) }
        val intent = IntentAnalyzer.analyze(input, repoContext)
        val missingInformation = MissingInformationAnalyzer.analyze(input, intent, repoContext)
        return PromptComposer.compose(input, intent, missingInformation, repoContext, failure)
    }

    /**
     * Re-composes [prompt] under [operation] against the same [original] input the composer needs
     * to rebuild from scratch (repo context, conversation history, etc.) — this is template
     * re-selection, never an LLM rewrite of [prompt]'s existing text (see this object's own doc
     * comment on why this module takes no LLM dependency).
     */
    fun refine(prompt: RegeneratedPrompt, operation: RegenerationOperation, original: RegenerationInput): RegeneratedPrompt {
        val adaptedTarget = when (operation) {
            RegenerationOperation.ADAPT_CLAUDE -> TargetModel.CLAUDE_CODE
            RegenerationOperation.ADAPT_CODEX -> TargetModel.CODEX
            RegenerationOperation.ADAPT_GPT -> TargetModel.GPT
            RegenerationOperation.ADAPT_GEMINI -> TargetModel.GEMINI
            RegenerationOperation.ADAPT_LOCAL -> TargetModel.LOCAL_MODEL
            RegenerationOperation.SHORTER, RegenerationOperation.MORE_DETAILED, RegenerationOperation.MORE_TECHNICAL -> prompt.targetModel
        }

        val rebuiltInput = original.copy(targetModel = adaptedTarget)
        val repoContext = rebuiltInput.repoRoot?.let { RepoContextScanner.scan(it) }
        val failure = rebuiltInput.previousAiResponse?.let { FailureClassifier.classify(it) }
        val intent = IntentAnalyzer.analyze(rebuiltInput, repoContext)
        val missingInformation = MissingInformationAnalyzer.analyze(rebuiltInput, intent, repoContext)
        val recomposed = PromptComposer.compose(rebuiltInput, intent, missingInformation, repoContext, failure)

        return when (operation) {
            RegenerationOperation.SHORTER -> shorten(recomposed)
            RegenerationOperation.MORE_DETAILED -> recomposed
            RegenerationOperation.MORE_TECHNICAL -> moreTechnical(recomposed)
            else -> recomposed
        }
    }

    /** Keeps only the task line, the model-specific guidance, and acceptance criteria — drops elaborated bullet explanations. */
    private fun shorten(prompt: RegeneratedPrompt): RegeneratedPrompt {
        val lines = prompt.optimizedPrompt.split("\n\n")
        val kept = lines.filter { section ->
            section.startsWith("#") || section.startsWith("Task:") || section.contains("guidance:") ||
                section.startsWith("Acceptance criteria:")
        }
        return prompt.copy(optimizedPrompt = kept.joinToString("\n\n"))
    }

    private fun moreTechnical(prompt: RegeneratedPrompt): RegeneratedPrompt {
        val technicalClause = "Be technically precise: name exact file paths, function/class signatures, " +
            "and the exact commands to run, rather than describing changes in general terms."
        return prompt.copy(optimizedPrompt = prompt.optimizedPrompt + "\n\n" + technicalClause)
    }
}
