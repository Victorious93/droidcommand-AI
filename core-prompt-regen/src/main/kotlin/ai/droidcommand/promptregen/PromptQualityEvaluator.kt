package ai.droidcommand.promptregen

import ai.droidcommand.agent.estimateTokens

enum class QualityDimension {
    CLARITY,
    COMPLETENESS,
    TECHNICAL_SPECIFICITY,
    INTERNAL_CONSISTENCY,
    PROVIDER_COMPATIBILITY,
    CONTEXT_EFFICIENCY,
    ACTIONABILITY,
    TESTABILITY,
}

/** [score] is 0..100. [reasons] explain every point added or removed, so the number is never opaque. */
data class DimensionScore(val dimension: QualityDimension, val score: Int, val reasons: List<String>)

data class PromptQualityReport(
    val dimensions: List<DimensionScore>,
    val overall: Int,
    val estimatedTokens: Int,
) {
    /** Always shown with the score: these are keyword/structure heuristics, not a prediction of provider behaviour. */
    val disclaimer: String =
        "Heuristic diagnostic from surface features of the text. Not a verified prediction of how any provider will respond."
}

/**
 * Explainable, deterministic, LLM-free prompt diagnostics (Part 2 §11). Each dimension starts from a
 * neutral baseline and moves on named, visible signals. Known limits: English only; it reads surface
 * wording, so a well-written prompt in unusual phrasing scores lower, and a keyword-stuffed prompt can
 * score higher than it deserves. Use it to point at what to look at, not to rank prompts absolutely.
 */
object PromptQualityEvaluator {
    private const val BASE = 50

    private val vagueWords = Regex("\\b(something|stuff|things?|somehow|whatever|etc|anything|maybe|kind of|sort of)\\b", RegexOption.IGNORE_CASE)
    private val actionVerbs = Regex(
        "\\b(implement|add|create|build|fix|write|refactor|update|remove|rename|migrate|test|verify|run|review|audit|document|inspect|generate|replace|extend)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val constraintWords = Regex("\\b(must|should not|must not|do not|don't|never|only|without|at most|at least|exactly|preserve)\\b", RegexOption.IGNORE_CASE)
    private val acceptanceWords = Regex("\\b(acceptance|done when|definition of done|expected|should (pass|return|produce|show)|success criteria|deliverable)\\b", RegexOption.IGNORE_CASE)
    private val testWords = Regex("\\b(test|tests|unit test|assert|verify|verification|gradlew|passes|passing|coverage)\\b", RegexOption.IGNORE_CASE)
    private val contextWords = Regex("\\b(repository|repo|module|file|class|function|existing|currently|background|context|version)\\b", RegexOption.IGNORE_CASE)
    private val pathLike = Regex("[\\w./-]+\\.(kt|kts|java|md|json|xml|yml|yaml|toml|gradle|py|ts|rs|go)\\b|\\b[\\w-]+/[\\w./-]+")
    private val identifierLike = Regex("\\b[a-z]+[A-Z]\\w*\\b|\\b[A-Z][a-z]+[A-Z]\\w*\\b|\\b\\w+_\\w+\\b|`[^`]+`")
    private val versionLike = Regex("\\b\\d+\\.\\d+(\\.\\d+)?\\b|\\bAPI\\s?\\d+\\b")
    private val filler = Regex(
        "\\b(please kindly|i would like you to|could you please|i was wondering if|as an ai|in order to|it is important to note that|basically|actually|just)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val contradictions = listOf(
        Regex("\\b(brief|briefly|concise|short)\\b", RegexOption.IGNORE_CASE) to Regex("\\b(exhaustive|comprehensive|in detail|detailed|thorough)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdo not (modify|change|edit)\\b", RegexOption.IGNORE_CASE) to Regex("\\b(rewrite|refactor|modify|rename)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(no dependencies|without (adding )?(new )?dependenc)", RegexOption.IGNORE_CASE) to Regex("\\badd (a |the )?(new )?(library|dependency|package)\\b", RegexOption.IGNORE_CASE),
    )

    /** Approximate window below which a prompt this long is risky; only used for [TargetModel.LOCAL_MODEL]. */
    private const val LOCAL_MODEL_SOFT_LIMIT_TOKENS = 1_500

    fun evaluate(prompt: String, target: TargetModel? = null): PromptQualityReport {
        val text = prompt.trim()
        val dims = listOf(
            clarity(text), completeness(text), specificity(text), consistency(text),
            compatibility(text, target), efficiency(text), actionability(text), testability(text),
        )
        return PromptQualityReport(dims, dims.sumOf { it.score } / dims.size, estimateTokens(text))
    }

    private class Scorer(val dimension: QualityDimension) {
        var score = BASE
        val reasons = mutableListOf<String>()
        fun add(points: Int, reason: String) { score += points; reasons += (if (points >= 0) "+$points " else "$points ") + reason }
        fun note(reason: String) { reasons += reason }
        fun build() = DimensionScore(dimension, score.coerceIn(0, 100), reasons.ifEmpty { listOf("no signals either way") })
    }

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun clarity(t: String) = Scorer(QualityDimension.CLARITY).apply {
        val n = words(t).size
        if (n == 0) { add(-BASE, "empty prompt"); return@apply }
        when {
            n < 5 -> add(-25, "only $n words; too short to state a task unambiguously")
            n < 12 -> add(-10, "short ($n words)")
            n in 20..400 -> add(15, "substantial statement of the task ($n words)")
        }
        val vague = vagueWords.findAll(t).count()
        if (vague > 0) add(-minOf(30, vague * 8), "$vague vague word(s) (e.g. 'something', 'stuff')")
        val avgSentence = n.toDouble() / t.split(Regex("[.!?]+\\s")).count { it.isNotBlank() }.coerceAtLeast(1)
        if (avgSentence > 45) add(-10, "very long sentences (avg ${avgSentence.toInt()} words)")
        if (t.contains('?') && !actionVerbs.containsMatchIn(t)) note("phrased as a question with no explicit action; fine for Q&A, unclear for a task")
    }.build()

    private fun completeness(t: String) = Scorer(QualityDimension.COMPLETENESS).apply {
        if (actionVerbs.containsMatchIn(t)) add(10, "states an action") else add(-15, "no explicit action verb")
        if (constraintWords.containsMatchIn(t)) add(10, "states constraints") else note("no constraints stated")
        if (acceptanceWords.containsMatchIn(t)) add(15, "states acceptance/expected outcome") else add(-5, "no acceptance criteria")
        if (contextWords.containsMatchIn(t)) add(10, "references project context") else add(-5, "no project context referenced")
        if (t.lines().count { it.isNotBlank() } >= 4) add(5, "multi-part structure")
    }.build()

    private fun specificity(t: String) = Scorer(QualityDimension.TECHNICAL_SPECIFICITY).apply {
        val paths = pathLike.findAll(t).count()
        val ids = identifierLike.findAll(t).count()
        val versions = versionLike.findAll(t).count()
        if (paths > 0) add(minOf(20, 8 + paths * 4), "$paths file path(s)/module reference(s)") else note("no file paths named")
        if (ids > 0) add(minOf(15, 5 + ids * 3), "$ids code identifier(s)") else note("no code identifiers named")
        if (versions > 0) add(8, "version/API-level numbers present")
        if (paths + ids + versions == 0) add(-15, "nothing concrete to anchor the request (no paths, identifiers or versions)")
    }.build()

    private fun consistency(t: String) = Scorer(QualityDimension.INTERNAL_CONSISTENCY).apply {
        add(20, "no contradiction patterns found (starting benefit of the doubt)")
        for ((a, b) in contradictions) {
            if (a.containsMatchIn(t) && b.containsMatchIn(t)) {
                add(-30, "possible conflict: '${a.find(t)!!.value}' vs '${b.find(t)!!.value}'")
            }
        }
        note("only a small fixed set of contradiction patterns is checked")
    }.build()

    private fun compatibility(t: String, target: TargetModel?) = Scorer(QualityDimension.PROVIDER_COMPATIBILITY).apply {
        if (target == null || target == TargetModel.GENERAL) { add(10, "no provider-specific constraints to check"); return@apply }
        val tokens = estimateTokens(t)
        if (target == TargetModel.LOCAL_MODEL) {
            if (tokens > LOCAL_MODEL_SOFT_LIMIT_TOKENS) add(-25, "~$tokens tokens is large for a small local model") else add(15, "size suits a small local model (~$tokens tokens)")
        } else {
            add(15, "no length concern for ${target.name} (~$tokens tokens)")
        }
        if (target == TargetModel.CLAUDE_CODE || target == TargetModel.CODEX) {
            if (pathLike.containsMatchIn(t) || contextWords.containsMatchIn(t)) add(10, "references repository context, which coding agents can act on")
            else add(-10, "coding agent target but no repository context referenced")
        }
        note("based on this project's own TargetModel templates, not a provider's published capability matrix")
    }.build()

    private fun efficiency(t: String) = Scorer(QualityDimension.CONTEXT_EFFICIENCY).apply {
        val ws = words(t.lowercase())
        if (ws.size < 8) { add(10, "too short for redundancy to matter"); return@apply }
        val ratio = ws.toSet().size.toDouble() / ws.size
        if (ratio >= 0.6) add(20, "low repetition (${(ratio * 100).toInt()}% unique words)") else add(-20, "high repetition (${(ratio * 100).toInt()}% unique words)")
        val dupSentences = t.split(Regex("(?<=[.!?\\n])\\s+")).map { it.trim().lowercase() }.filter { it.length > 15 }.let { it.size - it.toSet().size }
        if (dupSentences > 0) add(-15, "$dupSentences repeated sentence(s)")
        val fill = filler.findAll(t).count()
        if (fill > 0) add(-minOf(20, fill * 5), "$fill filler phrase(s)")
    }.build()

    private fun actionability(t: String) = Scorer(QualityDimension.ACTIONABILITY).apply {
        val first = t.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (actionVerbs.containsMatchIn(first)) add(25, "opens with an action") else if (actionVerbs.containsMatchIn(t)) add(10, "contains an action, but not up front") else add(-20, "no action verb anywhere")
        if (Regex("(?m)^\\s*(\\d+[.)]|[-*])\\s+").containsMatchIn(t)) add(10, "has an ordered or bulleted step list")
        if (vagueWords.containsMatchIn(first)) add(-10, "the opening line is vague")
    }.build()

    private fun testability(t: String) = Scorer(QualityDimension.TESTABILITY).apply {
        if (testWords.containsMatchIn(t)) add(20, "mentions tests/verification") else add(-10, "no tests or verification mentioned")
        if (acceptanceWords.containsMatchIn(t)) add(20, "has checkable acceptance criteria") else add(-5, "no checkable criteria")
        if (Regex("\\b\\d+\\b").containsMatchIn(t)) add(5, "contains concrete numbers that can be checked")
    }.build()
}
