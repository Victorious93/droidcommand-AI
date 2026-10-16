package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role

/** What [ContextFit.fit] decided about one conversation. */
data class FittedPrompt(
    /** The turns to send, oldest first. When [fits] is true the last one is the newest message of the request. */
    val messages: List<Message>,
    /** How many of the oldest turns were left out so the rest fits. */
    val dropped: Int,
    /** False when the newest message and the system prompt alone do not fit. Nothing is cut in that case. */
    val fits: Boolean,
)

/**
 * Keeps an on-device conversation inside the model's context window.
 *
 * A chat sends its whole history on every turn, and a local model's window is small (2,048 tokens for an imported
 * model). Without this, the native layer refuses any prompt that does not fit, so a chat stopped working after a few
 * turns and every later message failed. Here the oldest turns are left out instead, which is how chat apps with a
 * bounded window behave. Same rule as `ConversationContext`: oldest first, never the newest message, never the system
 * prompt.
 *
 * Sizes are estimated, not counted: there is no tokenizer on the JVM side. The estimate errs high so the window is
 * not overrun: one token per three ASCII characters (English averages about four) and one token per other character
 * (CJK text and emoji are about one token or more per character). The native layer still checks the real count and
 * refuses a prompt that does not fit, so a wrong estimate costs a failed turn, never a crash.
 */
object ContextFit {
    /** Tokens the chat template adds around each turn (role markers, end-of-turn marker). Generous on purpose. */
    private const val PER_MESSAGE_OVERHEAD = 12

    /** Tokens for the template's own preamble, and the generation prompt that opens the reply. */
    private const val TEMPLATE_RESERVE = 32

    /** The reply never gets more than half of the window, so a small window still leaves room for the prompt. */
    private const val MAX_REPLY_SHARE_DIVISOR = 2

    /** How many tokens of the window the reply may use: what was asked for, capped at half the window. */
    fun replyBudget(contextTokens: Int, requestedMaxTokens: Int): Int =
        requestedMaxTokens.coerceIn(1, maxOf(1, contextTokens / MAX_REPLY_SHARE_DIVISOR))

    /** How many tokens the prompt (system text and turns) may use once the reply and the template are accounted for. */
    fun promptBudget(contextTokens: Int, replyTokens: Int): Int = contextTokens - replyTokens - TEMPLATE_RESERVE

    fun estimateTokens(text: String): Int {
        var ascii = 0
        var other = 0
        for (ch in text) if (ch.code < 128) ascii++ else other++
        return (ascii + 2) / 3 + other
    }

    fun fit(systemPrompt: String?, messages: List<Message>, promptBudgetTokens: Int): FittedPrompt {
        var remaining = promptBudgetTokens - (systemPrompt?.let { estimateTokens(it) + PER_MESSAGE_OVERHEAD } ?: 0)
        val kept = ArrayDeque<Message>()
        for (message in messages.asReversed()) {
            val cost = cost(message)
            // The newest message is always kept; whether it fits decides the result. Older ones stay only while they fit.
            if (kept.isNotEmpty() && cost > remaining) break
            kept.addFirst(message)
            remaining -= cost
        }
        // Negative only when the system prompt, or the newest message on top of it, is itself too big.
        val fits = remaining >= 0
        // A history that starts on a reply (its question was dropped) reads oddly and some chat templates reject it.
        while (kept.size > 1 && kept.first().role != Role.USER) kept.removeFirst()
        return FittedPrompt(messages = kept.toList(), dropped = messages.size - kept.size, fits = fits)
    }

    // A SYSTEM-role message is dropped by the native backend, so it costs nothing here.
    private fun cost(message: Message): Int =
        if (message.role == Role.SYSTEM) 0 else estimateTokens(message.content) + PER_MESSAGE_OVERHEAD
}
