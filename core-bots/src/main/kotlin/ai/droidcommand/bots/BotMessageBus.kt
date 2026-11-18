package ai.droidcommand.bots

/** A message one bot sent another. [text] is untrusted data for the recipient, never an instruction from the owner. */
data class BotMessage(val from: String, val to: String, val text: String, val sentAtMillis: Long, val depth: Int)

sealed class BotSendResult {
    data class Delivered(val message: BotMessage) : BotSendResult()
    data class Denied(val reason: String) : BotSendResult()
}

/**
 * Delivers messages between bots, enforcing [BotPeerPolicy] plus the limits that keep a bot pair from looping
 * forever or flooding a mailbox:
 * - the link must be allowed (default deny, directed);
 * - [maxChars] per message, [maxMailbox] pending messages per recipient;
 * - [maxDepth]: a reply to a message carries `depth + 1` and is refused past the limit, so A→B→A→B chains end;
 * - [maxPerMinute] sends per sender in a rolling minute.
 *
 * Recipients read with [drain]; nothing here runs a bot or calls a model. The bus is in-memory: pending
 * messages do not survive a process restart. [policy] is read on every send, so a revoke takes effect at once.
 */
class BotMessageBus(
    private val policy: () -> BotPeerPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxChars: Int = 2_000,
    private val maxMailbox: Int = 20,
    private val maxDepth: Int = 4,
    private val maxPerMinute: Int = 10,
) {
    private val lock = Any()
    private val mailboxes = mutableMapOf<String, ArrayDeque<BotMessage>>()
    private val recentSends = mutableMapOf<String, ArrayDeque<Long>>()

    /** Deepest message a bot has read since it last sent one; its next send counts as a reply to that. */
    private val lastReadDepth = mutableMapOf<String, Int>()

    /**
     * [inReplyTo] is the message the sender is answering, if any; it sets the new message's depth. When it is
     * null, a bot that has read messages since its last send is treated as replying to the deepest of them, so
     * a model calling the tool cannot restart the chain by simply not saying it is a reply.
     */
    fun send(from: String, to: String, text: String, inReplyTo: BotMessage? = null): BotSendResult = synchronized(lock) {
        val body = text.trim()
        val depth = (inReplyTo?.depth ?: lastReadDepth[from] ?: -1) + 1
        val now = clock()
        val refusal = when {
            body.isEmpty() -> "Message is empty."
            body.length > maxChars -> "Message is longer than $maxChars characters."
            !policy().mayMessage(from, to) -> "This bot is not allowed to message that bot."
            depth > maxDepth -> "Reply chain is too long (limit $maxDepth); stop and tell the owner instead."
            (mailboxes[to]?.size ?: 0) >= maxMailbox -> "That bot's mailbox is full."
            sendsInLastMinute(from, now) >= maxPerMinute -> "Too many messages sent in the last minute."
            else -> null
        }
        if (refusal != null) return BotSendResult.Denied(refusal)
        recentSends.getOrPut(from) { ArrayDeque() }.addLast(now)
        lastReadDepth.remove(from)
        val message = BotMessage(from, to, body, now, depth)
        mailboxes.getOrPut(to) { ArrayDeque() }.addLast(message)
        BotSendResult.Delivered(message)
    }

    /** Takes and clears [botId]'s pending messages, oldest first. */
    fun drain(botId: String): List<BotMessage> = synchronized(lock) {
        val taken = mailboxes.remove(botId)?.toList().orEmpty()
        taken.maxOfOrNull { it.depth }?.let { lastReadDepth[botId] = it }
        taken
    }

    fun pendingCount(botId: String): Int = synchronized(lock) { mailboxes[botId]?.size ?: 0 }

    /** Drops a deleted bot's mailbox and send history. */
    fun forget(botId: String) = synchronized(lock) {
        mailboxes.remove(botId)
        recentSends.remove(botId)
        lastReadDepth.remove(botId)
        mailboxes.values.forEach { box -> box.removeAll { it.from == botId } }
    }

    private fun sendsInLastMinute(from: String, now: Long): Int {
        val times = recentSends[from] ?: return 0
        while (times.isNotEmpty() && now - times.first() >= 60_000) times.removeFirst()
        return times.size
    }
}
