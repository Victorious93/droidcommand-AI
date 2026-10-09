package ai.droidcommand.promptregen

enum class RequirementStatus {
    /** The requirement's wording appears verbatim (ignoring case/punctuation) in the regenerated prompt. */
    PRESERVED,

    /** A close but not identical match exists: the requirement survived in reworded form. */
    CLARIFIED,

    /** No match found. The user must confirm the requirement was not silently dropped. */
    FLAGGED,
}

data class RequirementChange(val requirement: String, val status: RequirementStatus, val matchedLine: String?)

data class PromptChangeSummary(
    val requirements: List<RequirementChange>,
    /** Lines present in the regenerated prompt with no counterpart in the original (headings, scaffolding, criteria). */
    val added: List<String>,
) {
    val preserved get() = requirements.filter { it.status == RequirementStatus.PRESERVED }
    val clarified get() = requirements.filter { it.status == RequirementStatus.CLARIFIED }
    val flagged get() = requirements.filter { it.status == RequirementStatus.FLAGGED }

    /** True only when nothing is flagged. Absence of flags is not proof of equivalent meaning — it is the absence of a detected loss. */
    val noDetectedLoss: Boolean get() = flagged.isEmpty()
}

/**
 * Compares an original prompt with its regeneration so the user can see what was kept, reworded,
 * added, or possibly lost (Part 2 §9, §11: "do not silently delete essential requirements").
 *
 * A *requirement* here is a sentence/line of the original that carries a constraint or instruction
 * signal (modal/negation words, imperative verbs, file paths, numbers, quoted/backticked terms).
 * That is a deliberately conservative surface heuristic: it can miss an implicit requirement and can
 * treat a plain statement as one. Matching is token overlap, not semantic equivalence.
 */
object PromptChangeSummarizer {
    private val signal = Regex(
        "\\b(must|should|need|needs|require[sd]?|do not|don't|never|only|without|always|at least|at most|exactly|" +
            "add|create|build|fix|implement|write|refactor|update|remove|rename|migrate|test|verify|run|review|audit|document|" +
            "preserve|keep|avoid|ensure)\\b|\\d|`[^`]+`|\"[^\"]+\"|[\\w./-]+\\.(kt|kts|java|md|json|xml|yml|yaml|py|ts|rs|go)\\b|\\b[\\w-]+/[\\w./-]+",
        RegexOption.IGNORE_CASE,
    )
    private const val CLARIFIED_OVERLAP = 0.6

    fun summarize(original: String, regenerated: String): PromptChangeSummary {
        val newLines = regenerated.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val newNormalized = newLines.map(::normalize)
        val newWhole = normalize(regenerated)

        val reqs = splitUnits(original).filter { signal.containsMatchIn(it) }.map { unit ->
            val norm = normalize(unit)
            val tokens = norm.split(' ').toSet()
            when {
                norm.isNotEmpty() && newWhole.contains(norm) ->
                    RequirementChange(unit, RequirementStatus.PRESERVED, newLines.firstOrNull { normalize(it).contains(norm) })
                else -> {
                    val (idx, overlap) = newNormalized.mapIndexed { i, l -> i to containment(tokens, l.split(' ').toSet()) }.maxByOrNull { it.second } ?: (-1 to 0.0)
                    if (idx >= 0 && overlap >= CLARIFIED_OVERLAP) RequirementChange(unit, RequirementStatus.CLARIFIED, newLines[idx])
                    else RequirementChange(unit, RequirementStatus.FLAGGED, null)
                }
            }
        }

        val originalNorms = splitUnits(original).map(::normalize).toSet()
        val originalTokens = normalize(original).split(' ').toSet()
        val added = newLines.filter { line ->
            val n = normalize(line)
            n.isNotEmpty() && n !in originalNorms && containment(n.split(' ').toSet(), originalTokens) < CLARIFIED_OVERLAP
        }
        return PromptChangeSummary(reqs, added)
    }

    private fun splitUnits(text: String): List<String> =
        text.lines().flatMap { it.split(Regex("(?<=[.!?])\\s+")) }
            .map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length >= 4 }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}./_`-]+"), " ").replace(Regex("[.](?=\\s|$)"), "")
            .trim().replace(Regex("\\s+"), " ")

    /** Share of [needle]'s tokens present in [haystack]. */
    private fun containment(needle: Set<String>, haystack: Set<String>): Double =
        if (needle.isEmpty()) 0.0 else needle.count { it in haystack }.toDouble() / needle.size
}
