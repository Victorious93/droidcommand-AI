package ai.droidcommand.hackerai

// Ported from hackeraiETC/lib/ai/subagents/runtime-recovery.ts

enum class ProviderErrorCategory {
    RATE_LIMITED,
    PROVIDER_5XX,
    STREAM_TERMINATED,
    TIMEOUT,
    CONTENT_BLOCKED,
    UNKNOWN,
}

data class SubagentProviderRetryDecision(
    val category: ProviderErrorCategory,
    val shouldRetry: Boolean,
    val delayMs: Long,
)

private val TRANSIENT_CATEGORIES = setOf(
    ProviderErrorCategory.RATE_LIMITED,
    ProviderErrorCategory.PROVIDER_5XX,
    ProviderErrorCategory.STREAM_TERMINATED,
    ProviderErrorCategory.TIMEOUT,
)

private val RECOVERABLE_CATEGORIES = TRANSIENT_CATEGORIES + ProviderErrorCategory.CONTENT_BLOCKED

fun isTransientProviderCategory(category: ProviderErrorCategory) = category in TRANSIENT_CATEGORIES
fun isRecoverableProviderCategory(category: ProviderErrorCategory) = category in RECOVERABLE_CATEGORIES

/**
 * Returns a retry decision for a provider error.
 * Exponential backoff: 750ms * 2^retriesUsed * jitter (0.75–1.25).
 * Max retries: SUBAGENT_MAX_PROVIDER_RECOVERY_RETRIES.
 */
fun getSubagentProviderRetryDecision(
    category: ProviderErrorCategory,
    retriesUsed: Int,
): SubagentProviderRetryDecision {
    val shouldRetry = isRecoverableProviderCategory(category) &&
        retriesUsed < SUBAGENT_MAX_PROVIDER_RECOVERY_RETRIES
    val base = 750L * (1L shl retriesUsed)
    // Deterministic jitter bracket — callers that need true randomness multiply by their own factor.
    val delayMs = if (shouldRetry) (base * 9L / 8L) else 0L
    return SubagentProviderRetryDecision(category, shouldRetry, delayMs)
}

/**
 * Classify an exception into a ProviderErrorCategory.
 * Maps common HTTP status codes and exception names to known categories.
 */
fun classifyProviderError(statusCode: Int?, exceptionName: String?): ProviderErrorCategory = when {
    statusCode == 429 -> ProviderErrorCategory.RATE_LIMITED
    statusCode != null && statusCode >= 500 -> ProviderErrorCategory.PROVIDER_5XX
    exceptionName?.contains("timeout", ignoreCase = true) == true -> ProviderErrorCategory.TIMEOUT
    exceptionName?.contains("stream", ignoreCase = true) == true -> ProviderErrorCategory.STREAM_TERMINATED
    else -> ProviderErrorCategory.UNKNOWN
}
