package ai.droidcommand.agent.memory

/**
 * Memory-context budget presets from the Second Brain spec. These are intentionally separate from
 * [ai.droidcommand.agent.TokenBudget] (1000/3000/8000/16000, the CAP-002 snapshot profiles): that enum
 * budgets a whole [ai.droidcommand.agent.ContextSnapshot], this one budgets only the retrieved-memory block.
 */
enum class MemoryBudgetPreset(val tokens: Int?) {
    TOKENS_1024(1_024),
    TOKENS_3072(3_072),
    TOKENS_8192(8_192),
    TOKENS_16384(16_384),

    /** Provider-aware: [AUTO_FRACTION] of whatever the model's window leaves free, capped at 16384. */
    AUTO(null),
}

object ContextBudgetPlanner {
    /** A heuristic share, not a tuned value. */
    const val AUTO_FRACTION = 0.25
    private const val AUTO_CAP = 16_384

    /**
     * Tokens available for retrieved memory: the preset (or the auto share), never more than what
     * remains of [contextWindow] after the system prompt, tool definitions, the user's prompt and the
     * output reservation. Returns 0 when nothing remains. All inputs are estimates unless the caller
     * has provider-exact counts.
     */
    fun memoryBudget(
        preset: MemoryBudgetPreset,
        contextWindow: Int,
        systemTokens: Int,
        toolTokens: Int,
        promptTokens: Int,
        reservedOutputTokens: Int,
    ): Int {
        require(contextWindow > 0) { "contextWindow must be > 0" }
        val free = (contextWindow - systemTokens - toolTokens - promptTokens - reservedOutputTokens).coerceAtLeast(0)
        val wanted = preset.tokens ?: minOf((free * AUTO_FRACTION).toInt(), AUTO_CAP)
        return minOf(wanted, free)
    }
}
