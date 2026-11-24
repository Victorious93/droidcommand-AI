package ai.droidcommand.app.communications

import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.communications.ArchivePoller
import ai.droidcommand.communications.ArchiveWire
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
class DraftRuntime(val inbox: DraftInbox, private val poller: ArchivePoller, private val channel: ArchiveChannel) {
    /** Conversations VictorSMS currently shares with the assistant, as (id, title). Empty if VictorSMS cannot be reached. Blocking: call off the main thread. */
    fun sharedConversations(): List<Pair<String, String>> {
        val a = channel.call(ArchiveWire.LIST, buildJsonObject { put("limit", 100) })
        if (a !is ArchiveAnswer.Ok) return emptyList()
        return runCatching {
            (a.result["conversations"] as JsonArray).map { (it as JsonObject).let { c -> c["id"]!!.jsonPrimitive.content to ((c["title"] as? JsonPrimitive)?.content ?: "") } }
        }.getOrDefault(emptyList())
    }

    /** Blocking: call off the main thread. */
    fun checkNow(): PollReport = poller.poll()
}
