package ai.droidcommand.app.communications

import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.communications.ArchivePoller
import ai.droidcommand.communications.ArchiveWire
import ai.droidcommand.communications.ConversationOrchestrator
import ai.droidcommand.communications.DraftInbox
import ai.droidcommand.communications.PollReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Victorious Framework: the owner-facing handle on the reply-draft pipeline. Drafts are text for you to read; nothing here
 * sends. Polling is manual ("Check now") for now: no background schedule exists yet, so nothing runs while the screen is closed.
 */
class DraftRuntime(
    val inbox: DraftInbox,
    private val poller: ArchivePoller,
    private val channel: ArchiveChannel,
    private val orchestrator: ConversationOrchestrator,
) {
    /** Conversations VictorSMS currently shares with the assistant, as (id, title). Empty if VictorSMS cannot be reached. Blocking: call off the main thread. */
    fun sharedConversations(): List<Pair<String, String>> {
        val a = channel.call(ArchiveWire.LIST, buildJsonObject { put("limit", 100) })
        if (a !is ArchiveAnswer.Ok) return emptyList()
        return runCatching {
            (a.result["conversations"] as JsonArray).map { (it as JsonObject).let { c -> c["id"]!!.jsonPrimitive.content to ((c["title"] as? JsonPrimitive)?.content ?: "") } }
        }.getOrDefault(emptyList())
    }

    /** Blocking: call off the main thread. Returns as soon as messages are queued; drafts finish on their own threads. */
    fun checkNow(): PollReport = poller.poll()

    /**
     * For the background worker: polls, then waits (bounded) for the drafts it just queued, because the process may be
     * frozen once the worker returns. Anything not finished in time is picked up again by the next check: a message is only
     * marked handled after its draft is stored.
     */
    fun checkAndWait(timeoutMs: Long): PollReport {
        val before = orchestrator.processed()
        val report = poller.poll()
        if (report.submitted > 0) orchestrator.awaitProcessed(before + report.submitted, timeoutMs)
        return report
    }
}
