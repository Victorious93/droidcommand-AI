package ai.droidcommand.promptregen

/** What a provider returned, as far as the caller knows. [httpStatus] is `null` for a local/offline provider. */
data class ProviderOutcome(val httpStatus: Int?, val body: String)

enum class OutcomeCategory {
    SUCCESS,
    AUTH_FAILURE,
    RATE_LIMITED,
    CONTEXT_OVERFLOW,
    UNSUPPORTED_FEATURE,
    API_ERROR,
    POLICY_REFUSAL,

    /** The model asked for more information or said the request was unclear — not a refusal. */
    NEEDS_CLARIFICATION,
}

data class OutcomeDiagnosis(
    val category: OutcomeCategory,
    /** The literal text that triggered the category (or the status code), so the reviewer can check it. */
    val evidence: String?,
    val suggestion: String,
    /** Always true for anything but [OutcomeCategory.SUCCESS]: a failed request is never resubmitted without the user's approval. */
    val requiresUserApproval: Boolean,
)

/**
 * Distinguishes a provider *policy refusal* from authentication failures, rate limits, context
 * overflow, unsupported features, plain API errors, and clarification requests (Part 2 §12). Pattern
 * based and English-only; an unrecognised failure body with an error status falls to
 * [OutcomeCategory.API_ERROR], and an unrecognised 200 body is treated as [OutcomeCategory.SUCCESS] —
 * the diagnoser never invents a refusal it cannot point at.
 *
 * Suggestions deliberately never propose hiding intent, asserting authorization the user has not
 * stated, or phrasing a request to slip past a provider's policy. For a policy refusal the only
 * advice is to check the request for real ambiguity and, if the user genuinely has relevant context
 * (scope, purpose, authorization), to state it truthfully — or to use a different, appropriate tool.
 */
object RefusalDiagnostics {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val contextPatterns = listOf(
        rx("context (length|window)"), rx("maximum context"), rx("prompt is too long"),
        rx("too many tokens"), rx("exceeds? the (maximum|model'?s) (number of )?tokens"),
    )
    private val unsupportedPatterns = listOf(
        rx("does not support (tools?|function|vision|images?|streaming|json)"), rx("unsupported (parameter|feature|model)"),
        rx("not supported (for|by) this model"), rx("unknown parameter"),
    )
    private val authPatterns = listOf(rx("invalid (api )?key"), rx("incorrect api key"), rx("authentication"), rx("unauthorized"), rx("permission denied"))
    private val rateLimitPatterns = listOf(rx("rate limit"), rx("too many requests"), rx("quota"))
    private val clarificationPatterns = listOf(
        rx("(could|can) you (clarify|specify|provide more)"), rx("which (file|module|repository|project|one) do you mean"),
        rx("your request is (too )?(vague|ambiguous|unclear)"), rx("i('| a)?m not sure what you (want|mean)"),
    )
    private val policyPatterns = listOf(
        rx("i('m| am) sorry,? (but )?i (can'?t|cannot|won'?t|am unable to) (help|assist|provide|do)"),
        rx("i (can'?t|cannot|won'?t) (help|assist) with that"),
        rx("i (must|have to) decline"), rx("against (my|our|the) (guidelines|policy|policies|usage policy)"),
        rx("content policy"), rx("violates? (our|the) (usage )?polic"), rx("i('m| am) not able to (help|assist) with"),
    )

    fun diagnose(outcome: ProviderOutcome): OutcomeDiagnosis {
        val body = outcome.body
        val status = outcome.httpStatus
        fun hit(patterns: List<Regex>) = patterns.firstNotNullOfOrNull { it.find(body)?.value }

        // Structured transport failures first: a status code is stronger evidence than prose.
        val auth = hit(authPatterns)
        if (status == 401 || auth != null && status != null && status >= 400) {
            return fail(OutcomeCategory.AUTH_FAILURE, auth ?: "HTTP $status", "Check the API key / login for this provider. This is not a content refusal; rewriting the prompt will not help.")
        }
        if (status == 429 || status != null && status >= 400 && hit(rateLimitPatterns) != null) {
            return fail(OutcomeCategory.RATE_LIMITED, hit(rateLimitPatterns) ?: "HTTP 429", "Wait and retry later, or switch provider/model. The prompt is not the problem.")
        }
        if (status == 413 || hit(contextPatterns) != null && (status == null || status >= 400)) {
            return fail(OutcomeCategory.CONTEXT_OVERFLOW, hit(contextPatterns) ?: "HTTP 413", "The request is larger than the model's context window. Use Token Saver mode, a smaller memory budget, or a larger-context model.")
        }
        if (status != null && status >= 400 && hit(unsupportedPatterns) != null) {
            return fail(OutcomeCategory.UNSUPPORTED_FEATURE, hit(unsupportedPatterns), "This model/provider does not support a feature the request used. Pick a capable model or drop the feature.")
        }
        if (status != null && status >= 400) {
            return fail(OutcomeCategory.API_ERROR, "HTTP $status", "Provider-side or transport error, not a content decision. Retry later or check the provider's status.")
        }

        hit(policyPatterns)?.let {
            return fail(
                OutcomeCategory.POLICY_REFUSAL, it,
                "The provider declined the request. Check whether the request was ambiguous about what you actually need. " +
                    "If you have real context the provider could not know (purpose, scope, authorization you actually hold), you may state it truthfully. " +
                    "This tool will not conceal intent or work around a provider's policy; if the task is not something this provider supports, use a different, appropriate tool.",
            )
        }
        hit(clarificationPatterns)?.let {
            return fail(OutcomeCategory.NEEDS_CLARIFICATION, it, "The model needs more detail, not a different request. Answer its question or add the missing specifics.")
        }
        return OutcomeDiagnosis(OutcomeCategory.SUCCESS, null, "No failure detected.", requiresUserApproval = false)
    }

    private fun fail(category: OutcomeCategory, evidence: String?, suggestion: String) =
        OutcomeDiagnosis(category, evidence, suggestion, requiresUserApproval = true)
}

sealed interface RetryDecision {
    data object Allowed : RetryDecision
    data class Blocked(val reason: String) : RetryDecision
}

/**
 * Gates resubmission after a failure (Part 2 §12: configurable limits, no uncontrolled loops, no
 * re-sending a materially unchanged request after a clear refusal). It decides; it never sends.
 * Call [recordAttempt] for every submission and [check] before the next one.
 */
class RetryGuard(
    private val maxAttempts: Int = 3,
    /** Token-set similarity at or above which a prompt counts as "materially unchanged". */
    private val unchangedThreshold: Double = 0.9,
) {
    private val attempts = mutableListOf<Set<String>>()

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(unchangedThreshold in 0.0..1.0) { "unchangedThreshold must be within 0..1" }
    }

    val attemptCount: Int get() = attempts.size

    fun recordAttempt(prompt: String) { attempts += tokens(prompt) }

    fun reset() = attempts.clear()

    /** @param userApproved whether the user has reviewed and approved sending [prompt] again. */
    fun check(prompt: String, last: OutcomeDiagnosis, userApproved: Boolean): RetryDecision {
        if (last.category == OutcomeCategory.SUCCESS) return RetryDecision.Allowed
        if (attempts.size >= maxAttempts) return RetryDecision.Blocked("attempt limit reached ($maxAttempts)")
        if (last.requiresUserApproval && !userApproved) return RetryDecision.Blocked("the user has not approved resubmission")
        val now = tokens(prompt)
        val unchanged = attempts.any { similarity(it, now) >= unchangedThreshold }
        val resend = when (last.category) {
            // Transient transport conditions are legitimately retried with the same prompt.
            OutcomeCategory.RATE_LIMITED, OutcomeCategory.API_ERROR -> false
            else -> unchanged
        }
        if (resend) return RetryDecision.Blocked("the prompt is materially unchanged since a ${last.category} outcome; change it or resolve the cause first")
        return RetryDecision.Allowed
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.intersect(b).size
        return inter.toDouble() / (a.size + b.size - inter)
    }
}
