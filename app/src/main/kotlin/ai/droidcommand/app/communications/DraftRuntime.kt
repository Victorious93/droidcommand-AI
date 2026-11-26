package ai.droidcommand.app.communications

import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.communications.ArchivePoller
import ai.droidcommand.communications.ArchiveWire
import ai.droidcommand.communications.ConversationOrchestrator
import ai.droidcommand.communications.DraftInbox
import ai.droidcommand.communications.PollReport
import ai.droidcommand.fleet.messenger.ExternalNodeCatalog
import ai.droidcommand.fleet.messenger.LiveCallResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Victorious Framework: the owner-facing handle on the reply-draft pipeline. The owner can hand a draft to VictorSMS for review.
 */
class DraftRuntime(
    val inbox: DraftInbox,
    private val poller: ArchivePoller,
    private val channel: ArchiveChannel,
    private val orchestrator: ConversationOrchestrator,
    private val stopped: () -> Boolean,
    private val reviewCall: (String, JsonObject) -> LiveCallResult,
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

    /** Called by the owner from Communications. Only VictorSMS can resolve the number and submit the text. */
    fun reviewDraft(id: String, statusOnly: Boolean): String {
        if (!statusOnly && stopped()) return "Emergency stop is on."
        val draft = inbox.pending().firstOrNull { it.id == id && it.text.isNotBlank() }
            ?: return "That reply draft is no longer available."
        val draftId = "reply-${draft.conversation}-${draft.messageId}"
        val op = if (statusOnly) ExternalNodeCatalog.VICTOR_SMS_DRAFT_STATUS else ExternalNodeCatalog.VICTOR_SMS_DRAFT_FROM_ARCHIVE
        val params = buildJsonObject {
            put("draftId", draftId)
            if (!statusOnly) {
                put("conversation", draft.conversation)
                put("body", draft.text)
            }
        }
        return when (val result = reviewCall(op, params)) {
            is LiveCallResult.Success -> when ((result.payload["state"] as? JsonPrimitive)?.content) {
                "received" -> "Waiting in VictorSMS → Settings → Drafts to review."
                "submitted" -> "VictorSMS submitted the draft."
                "declined" -> "The draft was declined or expired in VictorSMS."
                "failed" -> "VictorSMS reported a failed submission."
                "unknown" -> "Submission outcome unknown. Check the SMS conversation before trying again."
                else -> "VictorSMS returned an unrecognized draft status."
            }
            is LiveCallResult.RemoteError -> when (result.category) {
                "unsupported_operation" -> "Update VictorSMS to a version with assistant draft review."
                "permission_denied" -> "Grant DroidCommand-AI Drafts and Archive read in VictorSMS → Connected apps."
                "not_found" -> {
                    if (statusOnly) "VictorSMS has no record of this draft." else "Share this conversation with the assistant in VictorSMS."
                }
                else -> result.message
            }
            is LiveCallResult.Timeout -> "VictorSMS did not answer. Check Drafts to review in VictorSMS."
            else -> result.summary()
        }
    }

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
