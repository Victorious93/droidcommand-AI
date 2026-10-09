package ai.droidcommand.agent.memory

/**
 * One request's numbers. Everything named `estimated*` is an [ai.droidcommand.agent.estimateTokens]
 * figure (chars/4); `reported*` is what the provider returned and is `null` when it returned nothing.
 * The two are never merged or substituted for each other.
 */
data class TokenUsageRecord(
    val estimatedOriginalPromptTokens: Int,
    val estimatedOptimizedPromptTokens: Int,
    val estimatedMemoryTokens: Int,
    val estimatedOverheadTokens: Int,
    val reportedInputTokens: Int? = null,
    val reportedOutputTokens: Int? = null,
    /** `true`/`false` when a retrieval cache was consulted for this request, `null` when none was. */
    val cacheHit: Boolean? = null,
    val retrievalMillis: Long? = null,
) {
    val estimatedTotalInputTokens: Int get() = estimatedOptimizedPromptTokens + estimatedMemoryTokens + estimatedOverheadTokens

    /** Positive = the optimized prompt is shorter. Prompt text only: memory and overhead are additions, not savings. */
    val estimatedPromptSavings: Int get() = estimatedOriginalPromptTokens - estimatedOptimizedPromptTokens
}

data class TokenUsageSummary(
    val requests: Int,
    val estimatedTotalInputTokens: Long,
    val estimatedPromptSavings: Long,
    val estimatedMemoryTokens: Long,
    /** Sum over only the requests that had provider-reported input counts. */
    val reportedInputTokens: Long,
    val requestsWithReportedUsage: Int,
    /** `null` when no request consulted a cache — there is no rate to report. */
    val cacheHitRate: Double?,
    val averageRetrievalMillis: Double?,
)

/** In-memory log of [TokenUsageRecord]s for the analytics view. Not persisted. */
class TokenUsageTracker(private val maxRecords: Int = 1_000) {
    private val records = ArrayDeque<TokenUsageRecord>()
    private val lock = Any()

    init {
        require(maxRecords >= 1) { "maxRecords must be >= 1" }
    }

    fun record(r: TokenUsageRecord) = synchronized(lock) {
        records.addLast(r)
        while (records.size > maxRecords) records.removeFirst()
    }

    fun all(): List<TokenUsageRecord> = synchronized(lock) { records.toList() }

    fun summary(): TokenUsageSummary {
        val rs = all()
        val withReported = rs.filter { it.reportedInputTokens != null }
        val cacheLookups = rs.mapNotNull { it.cacheHit }
        val latencies = rs.mapNotNull { it.retrievalMillis }
        return TokenUsageSummary(
            requests = rs.size,
            estimatedTotalInputTokens = rs.sumOf { it.estimatedTotalInputTokens.toLong() },
            estimatedPromptSavings = rs.sumOf { it.estimatedPromptSavings.toLong() },
            estimatedMemoryTokens = rs.sumOf { it.estimatedMemoryTokens.toLong() },
            reportedInputTokens = withReported.sumOf { it.reportedInputTokens!!.toLong() },
            requestsWithReportedUsage = withReported.size,
            cacheHitRate = if (cacheLookups.isEmpty()) null else cacheLookups.count { it }.toDouble() / cacheLookups.size,
            averageRetrievalMillis = if (latencies.isEmpty()) null else latencies.average(),
        )
    }
}
