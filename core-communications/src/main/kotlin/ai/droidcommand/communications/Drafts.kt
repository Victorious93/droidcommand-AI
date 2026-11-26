// Victorious Framework: the "draft inbox". New live messages in conversations the owner switched on get a drafted reply that
// waits for the owner. NOTHING in this file sends, calls or posts a message: a draft is text for a person to read.
//
// Safety rules, each pinned by a test in DraftsTest:
//  - nothing is polled unless the owner enabled that conversation, and enabling never replies to what was already there
//    (only live messages newer than the moment of enabling count; imported history never does, mirroring VictorSMS's own gate);
//  - a message is drafted once, also after a restart;
//  - the emergency stop halts polling and drafting;
//  - an unreachable VictorSMS, one bad conversation or a model failure is reported, never thrown and never hidden.
package ai.droidcommand.communications

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class Draft(
    val id: String,
    val conversation: String,
    val messageId: String,
    val text: String,
    val needsInfo: String? = null,
    val createdAtMs: Long,
    /** pending, dismissed or failed. A failed entry carries the reason in [text]; nothing is retried silently. */
    val state: String = "pending",
)

@Serializable
data class DraftState(
    val enabledAt: Map<String, Long> = emptyMap(),
    val drafts: List<Draft> = emptyList(),
    val handled: List<String> = emptyList(),
)

interface DraftStore {
    fun load(): DraftState

    fun save(state: DraftState)
}

class InMemoryDraftStore : DraftStore {
    private var s = DraftState()

    @Synchronized override fun load() = s

    @Synchronized override fun save(state: DraftState) {
        s = state
    }
}

class JsonFileDraftStore(private val file: File) : DraftStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized override fun load(): DraftState =
        if (!file.exists()) DraftState() else runCatching { json.decodeFromString<DraftState>(file.readText()) }.getOrElse { DraftState() }

    @Synchronized override fun save(state: DraftState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}

class DraftInbox(private val store: DraftStore = InMemoryDraftStore(), private val clock: () -> Long = System::currentTimeMillis, private val handledCap: Int = 5_000, private val draftCap: Int = 300) {
    private var state = store.load()

    private fun key(conversation: String, messageId: String) = "$conversation|$messageId"

    private fun persist() = store.save(state)

    @Synchronized fun enable(conversation: String) {
        if (conversation in state.enabledAt) return
        state = state.copy(enabledAt = state.enabledAt + (conversation to clock()))
        persist()
    }

    @Synchronized fun disable(conversation: String) {
        if (conversation !in state.enabledAt) return
        state = state.copy(enabledAt = state.enabledAt - conversation)
        persist()
    }

    @Synchronized fun enabledAt(): Map<String, Long> = state.enabledAt

    @Synchronized fun isHandled(conversation: String, messageId: String) = key(conversation, messageId) in state.handled

    @Synchronized fun markHandled(conversation: String, messageId: String) {
        state = state.copy(handled = (state.handled + key(conversation, messageId)).takeLast(handledCap))
        persist()
    }

    /** Records the outcome and marks the message handled in one write, so a crash cannot leave a draft that would be made twice. */
    @Synchronized fun add(conversation: String, messageId: String, text: String, needsInfo: String?, state_: String = "pending"): Draft {
        val d = Draft("d" + clock() + "-" + (state.drafts.size + 1), conversation, messageId, text, needsInfo, clock(), state_)
        state = state.copy(drafts = (state.drafts + d).takeLast(draftCap), handled = (state.handled + key(conversation, messageId)).takeLast(handledCap))
        persist()
        return d
    }

    @Synchronized fun drafts(): List<Draft> = state.drafts

    @Synchronized fun pending(): List<Draft> = state.drafts.filter { it.state == "pending" }

    /** Moves a draft from one of [from] to [to]. False if it is gone or in another state, so a stale tap changes nothing. */
    @Synchronized fun transition(id: String, from: Set<String>, to: String): Boolean {
        if (state.drafts.none { it.id == id && it.state in from }) return false
        state = state.copy(drafts = state.drafts.map { if (it.id == id) it.copy(state = to) else it })
        persist()
        return true
    }

    @Synchronized fun dismiss(id: String): Boolean {
        if (state.drafts.none { it.id == id && it.state != "dismissed" }) return false
        state = state.copy(drafts = state.drafts.map { if (it.id == id) it.copy(state = "dismissed") else it })
        persist()
        return true
    }
}

data class PollReport(
    val stopped: Boolean = false,
    val unavailable: String? = null,
    val submitted: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val problems: List<String> = emptyList(),
)

class ArchivePoller(
    private val channel: ArchiveChannel,
    private val inbox: DraftInbox,
    private val orchestrator: ConversationOrchestrator,
    private val stopped: () -> Boolean = { false },
    private val pageSize: Int = 20,
) {
    fun poll(): PollReport {
        if (stopped()) return PollReport(stopped = true)
        val enabled = inbox.enabledAt()
        if (enabled.isEmpty()) return PollReport()
        val list = when (val a = channel.call(ArchiveWire.LIST, buildJsonObject { put("limit", 100) })) {
            is ArchiveAnswer.Ok -> a.result
            is ArchiveAnswer.Refused -> return PollReport(unavailable = "VictorSMS refused: ${a.message}".take(200))
            is ArchiveAnswer.Unavailable -> return PollReport(unavailable = a.message.take(200))
            is ArchiveAnswer.Failed -> return PollReport(unavailable = a.message.take(200))
        }
        val shared = runCatching { list["conversations"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }.getOrNull()
            ?: return PollReport(unavailable = "VictorSMS answered in a form this app does not understand.")
        var submitted = 0
        var dup = 0
        var skipped = 0
        val problems = ArrayList<String>()
        for ((conv, since) in enabled) {
            if (conv !in shared) {
                problems.add("Conversation $conv is no longer shared with the assistant.")
                continue
            }
            val read = channel.call(
                ArchiveWire.READ,
                buildJsonObject {
                    put("conversation", conv)
                    put("limit", pageSize)
                    put("direction", "in")
                },
            )
            val msgs = when (read) {
                is ArchiveAnswer.Ok -> runCatching { read.result["messages"]!!.jsonArray }.getOrNull()
                else -> null
            }
            if (msgs == null) {
                problems.add("Could not read conversation $conv.")
                continue
            }
            for (el in msgs) {
                val m = el as? JsonObject ?: continue
                val id = (m["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val ts = (m["ts"] as? JsonPrimitive)?.longOrNull ?: continue
                val live = (m["origin"] as? JsonPrimitive)?.contentOrNull == "live" && (m["direction"] as? JsonPrimitive)?.contentOrNull == "in"
                if (!live || ts < since || inbox.isHandled(conv, "m$id")) {
                    skipped++
                    continue
                }
                when (orchestrator.submit(InboundMessage(conv, "m$id", (m["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(), ts))) {
                    Submit.ACCEPTED -> submitted++
                    Submit.DUPLICATE -> dup++
                    else -> skipped++
                }
            }
        }
        return PollReport(submitted = submitted, duplicates = dup, skipped = skipped, problems = problems)
    }
}

/** Drafts only. The reply text comes from [ReplyService] (validated, injection-contained); this engine never calls a send capability. */
class DraftTurnEngine(
    private val replies: ReplyService,
    private val backend: CommsBackend,
    private val inbox: DraftInbox,
    private val stopped: () -> Boolean = { false },
) : TurnEngine {
    override fun run(scope: TurnScope): TurnResult {
        val conv = scope.conversation
        val mid = scope.message.messageId
        if (stopped()) return TurnResult(null) // not marked handled: a later poll after the stop is released will draft it
        val r = runCatching {
            replies.generateReply(mapOf("conversation" to conv, "instruction" to "Reply to the newest message.", "max_messages" to "1"), null, backend)
        }.getOrElse { BackendResult.Failed("Drafting failed (${it.javaClass.simpleName}).") }
        when (r) {
            is BackendResult.Ok -> {
                val o = runCatching { Json.parseToJsonElement(r.json).jsonObject }.getOrNull()
                val text = (o?.get("messages") as? JsonArray)?.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
                val need = (o?.get("needs_info") as? JsonPrimitive)?.contentOrNull
                if (text.isBlank() && need == null) inbox.add(conv, mid, "No draft was produced.", null, "failed") else inbox.add(conv, mid, text, need)
            }
            is BackendResult.Refused -> inbox.add(conv, mid, "No draft: ${r.message}".take(300), null, "failed")
            is BackendResult.Failed -> inbox.add(conv, mid, "No draft: ${r.message}".take(300), null, "failed")
            else -> inbox.add(conv, mid, "No draft was produced.", null, "failed")
        }
        return TurnResult(null) // never a reply to deliver: drafts wait for the owner
    }
}
