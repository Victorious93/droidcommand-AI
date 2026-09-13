package ai.droidcommand.promptregen

import ai.droidcommand.agent.Message
import java.nio.file.Path

/**
 * The external coding AI a [RegeneratedPrompt] is optimized for. `GPT`/`GEMINI`/`LOCAL_MODEL`/
 * `GENERAL` all share one "General AI" composition ([PromptComposer]) — only [CLAUDE_CODE] and
 * [CODEX] get a genuinely distinct template, matching the depth the project owner's own spec gives
 * each: detailed, tool-specific guidance for Claude Code and Codex, a general role/objective/
 * context/constraints/output/uncertainty/verification shape for everything else.
 */
enum class TargetModel { CLAUDE_CODE, CODEX, GPT, GEMINI, LOCAL_MODEL, GENERAL }

/** What the user most likely wants, recovered from [RegenerationInput] rather than asked for outright. */
enum class IntentCategory {
    IMPLEMENT_FEATURE,
    FIX_BUG,
    EXPLAIN_ERROR,
    ANSWER_QUESTION,
    CONTINUE_WORKFLOW,
    IMPROVE_PROMPT,
    RESOLVE_BLOCKER,
    AMBIGUOUS,
}

/**
 * The exact twelve failure shapes named in the project owner's Prompt Regenerator spec's
 * "Failed-Response Regeneration" workflow. [FailureClassifier] never invents a thirteenth.
 */
enum class FailureCategory {
    MISSING_CONTEXT,
    AMBIGUOUS_TASK,
    REPOSITORY_NOT_INSPECTED,
    INCORRECT_ASSUMPTION,
    OVERLY_BROAD_CLARIFICATION,
    INCOMPLETE_IMPLEMENTATION,
    INCORRECT_TECHNICAL_INTERPRETATION,
    REFUSAL,
    UNSUPPORTED_CAPABILITY,
    HALLUCINATED_REQUIREMENT,
    FAILURE_TO_VERIFY,
    PREMATURE_CONCLUSION,
}

/**
 * A deliberately shallow, honest snapshot of repo state — [RepoContextScanner] reads real files
 * only (marker-file presence, markdown file names under `docs`, the branch name out of `.git/HEAD`). No `git log`,
 * no issue tracker query, no shelled-out `git` process: those would need a subprocess dependency
 * this module deliberately doesn't take on for a first slice (see [RepoContextScanner]'s own doc
 * comment).
 */
data class RepoContext(
    val hasClaudeMd: Boolean,
    val hasAgentsMd: Boolean,
    val hasReadme: Boolean,
    val docFiles: List<String>,
    val gitBranch: String?,
)

/**
 * Everything [PromptRegenerator.regenerate] can draw on. [repoRoot] is optional — omitting it
 * simply skips repo-aware context recovery rather than failing; [previousAiResponse] is optional
 * and, when present, routes the pipeline through [FailureClassifier] first; [targetModel] left
 * `null` means "no explicit preference," which [PromptComposer] resolves to [TargetModel.GENERAL].
 */
data class RegenerationInput(
    val rawInput: String,
    val conversationHistory: List<Message> = emptyList(),
    val repoRoot: Path? = null,
    val previousAiResponse: String? = null,
    val targetModel: TargetModel? = null,
)

/**
 * [referencedSubject] is only ever set when [rawInput] itself was too trivial to carry a subject
 * (e.g. "?") and one was recovered from [RegenerationInput.conversationHistory] instead — never
 * fabricated when history is empty, per the spec's own "if genuinely missing, say so" rule.
 */
data class IntentAnalysis(
    val category: IntentCategory,
    val alternatives: List<IntentCategory>,
    val reasoning: String,
    val referencedSubject: String?,
)

/**
 * [evidence] is the literal substring of the previous response that triggered this classification
 * — included so a caller/reviewer can see exactly what was matched, not just trust the category.
 * Deliberately carries no "was this actually recoverable from context" verdict: [FailureClassifier]
 * only ever sees the previous response's text, never [RepoContext] or
 * [RegenerationInput.conversationHistory], so it has no honest basis for that judgment — [PromptComposer]
 * makes it instead, since it is the component that actually holds both.
 */
data class FailureClassification(
    val category: FailureCategory,
    val evidence: String,
)

/** The seven-part output the spec's own "Output" section requires. */
data class RegeneratedPrompt(
    val optimizedPrompt: String,
    val targetModel: TargetModel,
    val recoveredIntent: String,
    val contextUsed: List<String>,
    val assumptions: List<String>,
    val missingInformation: List<String>,
    val acceptanceCriteria: List<String>,
)

/**
 * The "Allow: Make shorter / Make more detailed / Make more technical / Adapt for X" actions the
 * spec's "Output" section lists. [PromptRegenerator.refine] applies these as deterministic template
 * re-selection — never an LLM rewrite, since this module takes no LLM dependency (see module doc).
 */
enum class RegenerationOperation {
    SHORTER,
    MORE_DETAILED,
    MORE_TECHNICAL,
    ADAPT_CLAUDE,
    ADAPT_CODEX,
    ADAPT_GPT,
    ADAPT_GEMINI,
    ADAPT_LOCAL,
}
