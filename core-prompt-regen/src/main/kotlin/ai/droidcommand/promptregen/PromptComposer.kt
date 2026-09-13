package ai.droidcommand.promptregen

/**
 * Builds the final [RegeneratedPrompt] from the pipeline's earlier stages. Every clause here is a
 * fixed template string, not model-generated text — this module takes no LLM dependency (see
 * [PromptRegenerator]'s doc comment for why that's an honest choice, not a shortcut).
 */
object PromptComposer {
    /**
     * The spec's own explicit anti-hallucination requirements, phrased generically — no invented
     * `ROADMAP-###`/`CAP-###`/file/API name ever appears here, since the target agent, not this
     * composer, is the one that inspects the real repo and discovers what actually exists.
     */
    private val antiHallucinationClauses = listOf(
        "Inspect the repository before modifying anything.",
        "Never invent files, APIs, commands, issues, or roadmap/requirement identifiers you have not verified exist.",
        "Never claim a command succeeded unless you actually ran it and saw it succeed.",
        "Never claim tests passed unless you actually executed them.",
        "Never claim a file changed unless you actually changed it.",
        "Distinguish verified facts from assumptions explicitly.",
        "State genuinely missing information rather than guessing or fabricating it.",
        "Preserve existing architecture and behavior unless a change is explicitly required.",
        "Verify the final implementation before reporting it as complete.",
    )

    private val repoInspectionInstruction =
        "Inspect the repository's project instructions, README, roadmap, audit documentation, open " +
            "issues, and relevant source code. Determine the intended implementation from the available " +
            "project context before asking the user to restate information already present in the " +
            "repository."

    fun compose(
        input: RegenerationInput,
        intent: IntentAnalysis,
        missingInformation: List<String>,
        repoContext: RepoContext?,
        failure: FailureClassification?,
    ): RegeneratedPrompt {
        val targetModel = input.targetModel ?: TargetModel.GENERAL
        val contextUsed = mutableListOf<String>()
        val assumptions = mutableListOf<String>()

        val taskLine = taskLine(input, intent)

        val sections = mutableListOf<String>()

        if (failure != null) {
            sections.add(failureCorrectionSection(failure, repoContext))
            contextUsed.add("Previous AI response (classified as ${failure.category}: matched \"${failure.evidence}\")")
        }

        sections.add(taskLine)

        if (repoContext != null) {
            sections.add(repoInspectionInstruction)
            sections.add(repoFindingsLine(repoContext))
            contextUsed.add("Repository scan (CLAUDE.md=${repoContext.hasClaudeMd}, AGENTS.md=${repoContext.hasAgentsMd}, README.md=${repoContext.hasReadme}, docs=${repoContext.docFiles.size} file(s), branch=${repoContext.gitBranch ?: "unknown"})")
        } else {
            assumptions.add("No repository path was supplied, so this prompt does not assume any specific project structure.")
        }

        if (intent.referencedSubject != null) {
            sections.add("The user's most recent substantive request was: \"${intent.referencedSubject}\" — treat this as the subject of the current, otherwise context-free follow-up.")
            contextUsed.add("Conversation history (most recent substantive message)")
        }

        if (input.conversationHistory.isNotEmpty()) {
            contextUsed.add("${input.conversationHistory.size} prior conversation message(s)")
        }

        sections.add(modelSpecificSection(targetModel))
        sections.add("Requirements:\n" + antiHallucinationClauses.joinToString("\n") { "- $it" })

        val acceptanceCriteria = acceptanceCriteria(intent, repoContext)
        if (acceptanceCriteria.isNotEmpty()) {
            sections.add("Acceptance criteria:\n" + acceptanceCriteria.joinToString("\n") { "- $it" })
        }

        if (missingInformation.isNotEmpty()) {
            sections.add(
                "If the following is still genuinely missing after inspecting the repository, state it " +
                    "plainly rather than guessing:\n" + missingInformation.joinToString("\n") { "- $it" },
            )
        }

        val optimizedPrompt = (listOf(modelHeader(targetModel)) + sections).joinToString("\n\n")

        return RegeneratedPrompt(
            optimizedPrompt = optimizedPrompt,
            targetModel = targetModel,
            recoveredIntent = "${intent.category}: ${intent.reasoning}",
            contextUsed = contextUsed,
            assumptions = assumptions,
            missingInformation = missingInformation,
            acceptanceCriteria = acceptanceCriteria,
        )
    }

    private fun taskLine(input: RegenerationInput, intent: IntentAnalysis): String =
        if (intent.referencedSubject != null) {
            "Task: continue the following in-progress work — \"${intent.referencedSubject}\" (the user's own follow-up was: \"${input.rawInput}\")."
        } else {
            "Task: ${input.rawInput}"
        }

    private fun failureCorrectionSection(failure: FailureClassification, repoContext: RepoContext?): String {
        val recoveryNote = if (repoContext != null) {
            "Before asking for a new task description, inspect the repository's project instructions, " +
                "roadmap, audit documentation, current branch, and relevant existing implementation. Use " +
                "the existing context to determine the intended implementation."
        } else {
            "No repository context was supplied to this regeneration, so if the missing information " +
                "genuinely isn't available, say so specifically rather than asking a broad question again."
        }
        return "The previous response was classified as a ${failure.category} failure (it matched: " +
            "\"${failure.evidence}\"). $recoveryNote If multiple interpretations remain possible, identify " +
            "the specific ambiguity and ask only for that missing decision — not the whole task again."
    }

    private fun repoFindingsLine(repoContext: RepoContext): String {
        val found = buildList {
            if (repoContext.hasClaudeMd) add("CLAUDE.md")
            if (repoContext.hasAgentsMd) add("AGENTS.md")
            if (repoContext.hasReadme) add("README.md")
            if (repoContext.docFiles.isNotEmpty()) add("docs/ (${repoContext.docFiles.joinToString(", ")})")
        }
        val branchNote = repoContext.gitBranch?.let { " Current branch: $it." } ?: ""
        return if (found.isEmpty()) {
            "No CLAUDE.md, AGENTS.md, README.md, or docs/ were found at the given repository path." + branchNote
        } else {
            "Found in the repository: ${found.joinToString(", ")}." + branchNote
        }
    }

    private fun modelHeader(targetModel: TargetModel): String = when (targetModel) {
        TargetModel.CLAUDE_CODE -> "# Prompt for Claude / Claude Code"
        TargetModel.CODEX -> "# Prompt for OpenAI Codex"
        TargetModel.GPT -> "# Prompt for GPT"
        TargetModel.GEMINI -> "# Prompt for Gemini"
        TargetModel.LOCAL_MODEL -> "# Prompt for a local/Ollama model"
        TargetModel.GENERAL -> "# Prompt for a general-purpose AI agent"
    }

    private fun modelSpecificSection(targetModel: TargetModel): String = when (targetModel) {
        TargetModel.CLAUDE_CODE ->
            "Claude Code guidance: read CLAUDE.md and the existing architecture before changing anything. " +
                "Check current git state (branch, working tree status) before committing. Add or update " +
                "tests for the change and run the full test suite plus a real build; report only what those " +
                "commands actually showed. Use real tool execution (file edits, shell commands, test runs) " +
                "rather than describing hypothetical output."
        TargetModel.CODEX ->
            "Codex guidance: read AGENTS.md and any other project instructions before changing anything. " +
                "Keep the change minimal and precisely scoped to the task above — resist unrelated " +
                "refactors. Run lint, type-check, and test commands the project already defines, and report " +
                "only their real output."
        else ->
            "General AI guidance — structure your response around:\n" +
                "- Role: what kind of task this is (implementation, bug fix, explanation, etc.).\n" +
                "- Objective: what a successful outcome looks like.\n" +
                "- Context: the repository/conversation information supplied above.\n" +
                "- Constraints: preserve existing behavior/architecture unless told otherwise.\n" +
                "- Required output: the concrete deliverable (code, explanation, or answer).\n" +
                "- Uncertainty: call out anything assumed rather than verified.\n" +
                "- Verification: how the result was actually checked, not just asserted."
    }

    private fun acceptanceCriteria(intent: IntentAnalysis, repoContext: RepoContext?): List<String> {
        val criteria = mutableListOf<String>()
        when (intent.category) {
            IntentCategory.IMPLEMENT_FEATURE -> {
                criteria.add("The new capability behaves as described and does not remove or break existing functionality.")
                criteria.add("Tests covering the new behavior exist and pass.")
            }
            IntentCategory.FIX_BUG -> {
                criteria.add("The described faulty behavior no longer occurs.")
                criteria.add("A test reproducing the original bug now passes.")
            }
            IntentCategory.EXPLAIN_ERROR -> {
                criteria.add("The root cause is identified with evidence (not just a guess).")
            }
            IntentCategory.CONTINUE_WORKFLOW, IntentCategory.RESOLVE_BLOCKER -> {
                criteria.add("The specific blocker or next step from the referenced prior work is actually addressed.")
            }
            IntentCategory.IMPROVE_PROMPT -> {
                criteria.add("The regenerated prompt is more specific and actionable than the original.")
            }
            IntentCategory.ANSWER_QUESTION -> {
                criteria.add("The answer is grounded in verified facts, with assumptions labeled as such.")
            }
            IntentCategory.AMBIGUOUS -> {
                // No acceptance criteria can be stated honestly until the task itself is known.
            }
        }
        if (repoContext != null) {
            criteria.add("The existing project structure and conventions are preserved.")
        }
        return criteria
    }
}
