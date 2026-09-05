package ai.droidcommand.llm

/**
 * Routes a request across an ordered list of [LlmProvider]s — e.g. a local
 * model first, then a remote/cloud one — falling back to the next provider
 * only on a transient, provider-level failure ([LlmError.Network],
 * [LlmError.Timeout], [LlmError.ModelUnavailable]). [LlmError.Authentication]
 * and [LlmError.InvalidResponse] are never retried against a different
 * provider: a bad API key or a malformed response is a configuration/bug
 * signal that trying a different provider would silently mask, not
 * transient load that a fallback should paper over. [LlmError.Cancelled] is
 * likewise never retried — the caller asked to stop.
 *
 * This is itself an [LlmProvider], so it composes with [LlmPlanner] (or
 * anything else built against the interface) with no change anywhere else —
 * deliberately avoiding a second, competing "model manager" concept.
 */
class ModelRouter(private val providers: List<LlmProvider>) : LlmProvider {
    init {
        require(providers.isNotEmpty()) { "ModelRouter requires at least one provider" }
    }

    override val config: LlmConfig = providers.first().config

    override fun complete(request: LlmRequest): LlmResponse {
        var lastError: LlmResponse.Error? = null
        for (provider in providers) {
            val response = provider.complete(request)
            if (response !is LlmResponse.Error) return response
            lastError = response
            if (!isRetryable(response.error)) return response
        }
        // Unreachable in practice (the loop above always returns on a non-retryable
        // error or exhausts providers having set lastError every time — providers is
        // never empty per the constructor check), kept only so this compiles without
        // a platform-type/!! at the boundary.
        return lastError ?: LlmResponse.Error(LlmError.ModelUnavailable("ModelRouter: no providers configured"))
    }

    private fun isRetryable(error: LlmError): Boolean = when (error) {
        is LlmError.Network, is LlmError.Timeout, is LlmError.ModelUnavailable -> true
        is LlmError.Authentication, is LlmError.InvalidResponse, is LlmError.Cancelled -> false
    }
}
