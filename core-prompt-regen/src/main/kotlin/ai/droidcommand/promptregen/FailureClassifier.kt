package ai.droidcommand.promptregen

/**
 * Pattern-matches a previous AI response's text against known failure shapes. Returns `null` when
 * nothing matches — a response this classifier doesn't recognize as failed is never forced into one
 * of the twelve [FailureCategory] buckets just to produce an answer; that would be exactly the kind
 * of fabrication this whole feature exists to prevent in *other* AIs' output.
 *
 * Ordered so the more specific/severe categories are checked before the generic ones (e.g. an
 * explicit "I don't have enough information" is [REFUSAL]-adjacent but more precisely
 * [MISSING_CONTEXT] when it's asking for something the repo likely already states — checked ahead
 * of a bare [REFUSAL] match on "I cannot").
 */
object FailureClassifier {
    private data class Rule(val category: FailureCategory, val patterns: List<Regex>)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val rules = listOf(
        Rule(
            FailureCategory.MISSING_CONTEXT,
            listOf(
                rx("i don'?t have enough (information|context) to proceed"),
                rx("(can you|could you) (provide|share|give me) more (information|context|details)"),
            ),
        ),
        Rule(
            FailureCategory.REPOSITORY_NOT_INSPECTED,
            listOf(
                rx("what task (would you|do you) (like|want) (me )?to (do|perform)"),
                rx("i (don'?t|do not) have access to (the|your) (repository|codebase|project)"),
                rx("please (restate|repeat|re-?describe) (the|your) (task|request|requirement)"),
            ),
        ),
        Rule(
            FailureCategory.AMBIGUOUS_TASK,
            listOf(
                rx("(your|the) request is (too )?(vague|ambiguous|unclear)"),
                rx("i'?m not sure what you (want|mean|are asking)"),
            ),
        ),
        Rule(
            FailureCategory.OVERLY_BROAD_CLARIFICATION,
            listOf(
                rx("what (exactly )?(do you want|would you like) (me )?to (do|change|build|fix)\\??$"),
                rx("(please )?(tell|let) me know what you (need|want)\\.?$"),
            ),
        ),
        Rule(
            FailureCategory.UNSUPPORTED_CAPABILITY,
            listOf(
                rx("i (can'?t|cannot|am unable to) (do|perform|access|run) that"),
                rx("(that'?s|this is) (outside|beyond) (my|the) (capabilities|scope)"),
            ),
        ),
        Rule(
            FailureCategory.HALLUCINATED_REQUIREMENT,
            listOf(
                rx("there is no such (file|roadmap item|requirement|api|module|issue)"),
                rx("(that|this) (roadmap item|requirement|api|file) does not exist"),
            ),
        ),
        Rule(
            FailureCategory.FAILURE_TO_VERIFY,
            listOf(
                rx("i (assumed|didn'?t (actually )?run|did not (actually )?run) (the )?(tests|build|command)"),
                rx("i (can'?t|cannot) confirm (this|that|it) (actually )?works"),
            ),
        ),
        Rule(
            FailureCategory.INCOMPLETE_IMPLEMENTATION,
            listOf(
                rx("(this|the) implementation is (partial|incomplete)"),
                rx("i (only|just) (stubbed|scaffolded) (this|it) out"),
            ),
        ),
        Rule(
            FailureCategory.PREMATURE_CONCLUSION,
            listOf(
                rx("(this|that) should (now )?work"),
                rx("i believe (this|that) (resolves|fixes|completes) (the|your) (issue|request|task)"),
            ),
        ),
        Rule(
            FailureCategory.INCORRECT_TECHNICAL_INTERPRETATION,
            listOf(
                rx("i (misunderstood|misinterpreted) (the|your) (request|task|requirement)"),
            ),
        ),
        Rule(
            FailureCategory.INCORRECT_ASSUMPTION,
            listOf(
                rx("i assumed (you meant|you wanted|that)"),
            ),
        ),
        Rule(
            FailureCategory.REFUSAL,
            listOf(
                rx("i'?m sorry,? (but )?i (can'?t|cannot|won'?t)"),
                rx("i (must|have to) decline"),
            ),
        ),
    )

    fun classify(previousAiResponse: String): FailureClassification? {
        for (rule in rules) {
            for (pattern in rule.patterns) {
                val match = pattern.find(previousAiResponse) ?: continue
                return FailureClassification(category = rule.category, evidence = match.value)
            }
        }
        return null
    }
}
