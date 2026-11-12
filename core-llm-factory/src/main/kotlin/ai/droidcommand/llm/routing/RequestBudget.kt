package ai.droidcommand.llm.routing

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.local.ContextFit

/**
 * An estimate of everything one outgoing request holds, in tokens, by part. Estimated with [ContextFit]'s rule, which
 * errs high (there is no tokenizer on this side), so a request judged to fit is not expected to overrun the window.
 *
 * [historyTokens] is the only part that may be shortened (oldest turns first). The rest is [fixedTokens]: the system
 * text, the new message with [retrievedTokens] of retrieved content attached to it, the tool schemas, the tool calls
 * and results of this turn, and the template overhead. [outputReserveTokens] is what was asked to be kept free for the
 * reply; a window keeps at most half of itself for it ([ContextFit.replyBudget]).
 */
data class RequestBudget(
    val systemTokens: Int,
    val historyTokens: Int,
    val messageTokens: Int,
    val retrievedTokens: Int,
    val toolSchemaTokens: Int,
    val toolResultTokens: Int,
    val outputReserveTokens: Int,
    val overheadTokens: Int,
) {
    val fixedTokens: Int get() = systemTokens + messageTokens + retrievedTokens + toolSchemaTokens + toolResultTokens + overheadTokens

    /** The whole request with all of its history and the full output reservation. */
    val totalTokens: Int get() = fixedTokens + historyTokens + outputReserveTokens

    /** Tokens of a [windowTokens]-token window kept free for the reply. */
    fun replyTokens(windowTokens: Int): Int = ContextFit.replyBudget(windowTokens, outputReserveTokens)

    /** True when the request fits [windowTokens] once history may be shortened as far as needed. */
    fun fitsWithoutHistory(windowTokens: Int): Boolean = fixedTokens + replyTokens(windowTokens) <= windowTokens

    /** True when nothing has to be left out. */
    fun fitsWhole(windowTokens: Int): Boolean = fixedTokens + historyTokens + replyTokens(windowTokens) <= windowTokens

    /** The smallest window [fitsWithoutHistory] accepts. */
    fun minimumWindowTokens(): Int =
        if (fixedTokens < outputReserveTokens) 2 * fixedTokens else fixedTokens + outputReserveTokens

    /** One line for the route display. */
    fun summary(): String =
        "about $totalTokens tokens (system $systemTokens, history $historyTokens, message $messageTokens, retrieved content $retrievedTokens, " +
            "tool schemas $toolSchemaTokens, tool results $toolResultTokens, reply reserve $outputReserveTokens, overhead $overheadTokens)"

    companion object {
        /** Tokens a chat template adds around each turn. Same generous figure [ContextFit] uses. */
        const val PER_MESSAGE_OVERHEAD = 12

        /** Template preamble and the prompt that opens the reply. Same figure [ContextFit] uses. */
        const val TEMPLATE_RESERVE = 32

        /** Per tool: the JSON wrapper around its name, description and parameters. */
        private const val PER_TOOL_OVERHEAD = 24

        fun toolSchemaTokens(tools: List<ToolSpec>): Int = tools.sumOf { tool ->
            PER_TOOL_OVERHEAD + ContextFit.estimateTokens(tool.name) + ContextFit.estimateTokens(tool.description) +
                (tool.parameterSchema?.parameters?.sumOf { ContextFit.estimateTokens(it.name) + ContextFit.estimateTokens(it.description) + 8 } ?: 0)
        }

        fun messageTokens(message: Message): Int =
            if (message.role == Role.SYSTEM) {
                0
            } else {
                ContextFit.estimateTokens(message.content) + PER_MESSAGE_OVERHEAD +
                    message.toolCalls.sumOf { ContextFit.estimateTokens(it.name) + ContextFit.estimateTokens(it.argumentsJson) + ContextFit.estimateTokens(it.id) + 8 }
            }

        /**
         * [history] is the stored conversation, [userText] what was typed, [outgoing] the message as sent (typed text
         * plus retrieved content), [exchange] this turn's tool calls and results so far.
         */
        fun of(
            systemPrompt: String?,
            history: List<Message>,
            userText: String,
            outgoing: String,
            tools: List<ToolSpec>,
            exchange: List<Message>,
            outputReserveTokens: Int,
        ): RequestBudget {
            val typed = ContextFit.estimateTokens(userText)
            return RequestBudget(
                systemTokens = systemPrompt?.let { ContextFit.estimateTokens(it) + PER_MESSAGE_OVERHEAD } ?: 0,
                historyTokens = history.sumOf(::messageTokens),
                messageTokens = typed + PER_MESSAGE_OVERHEAD,
                retrievedTokens = (ContextFit.estimateTokens(outgoing) - typed).coerceAtLeast(0),
                toolSchemaTokens = toolSchemaTokens(tools),
                toolResultTokens = exchange.sumOf(::messageTokens),
                outputReserveTokens = outputReserveTokens,
                overheadTokens = TEMPLATE_RESERVE,
            )
        }

        /**
         * The part of [history] to send to a [windowTokens]-token window next to the fixed parts of [budget], oldest
         * turns dropped first, never starting on a reply whose question was dropped. Returns null when even an empty
         * history does not fit.
         */
        fun fitHistory(budget: RequestBudget, history: List<Message>, windowTokens: Int): List<Message>? {
            if (!budget.fitsWithoutHistory(windowTokens)) return null
            var remaining = windowTokens - budget.replyTokens(windowTokens) - budget.fixedTokens
            val kept = ArrayDeque<Message>()
            for (message in history.asReversed()) {
                val cost = messageTokens(message)
                if (cost > remaining) break
                kept.addFirst(message)
                remaining -= cost
            }
            while (kept.isNotEmpty() && kept.first().role != Role.USER) kept.removeFirst()
            return kept.toList()
        }
    }
}
