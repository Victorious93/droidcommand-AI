package ai.droidcommand.communications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Victorious Framework: the VictorSMS connector (read-only). VictorSMS owns the conversation archive; this class only
 * asks it, over the paired EXTERNAL_NODE bridge, for what the owner allowed. Two gates sit on VictorSMS's side and
 * neither can be widened from here: the owner grants this app an "Archive read" op group, and the owner switches each
 * conversation on for the assistant. A conversation that is not switched on is answered exactly like one that does
 * not exist.
 *
 * What is served: `list_conversations`, `read_conversation`, `search_messages`, and the retrieval behind
 * `generate_reply` / `summarize_conversation`. Everything else VictorSMS owns (contacts, sending, calls, drafts,
 * imports, auto-reply) is reported unavailable. Nothing is simulated.
 */
object ArchiveWire {
    const val VERSION = 1
    const val CAPABILITIES = "comms.capabilities"
    const val LIST = "comms.conversations.list"
    const val READ = "comms.conversation.read"
    const val SEARCH = "comms.messages.search"
    const val CONTEXT = "comms.context.retrieve"
    val OPS: Set<String> = setOf(CAPABILITIES, LIST, READ, SEARCH, CONTEXT)
}

/** VictorSMS's conversation-addressed draft hand-off. VictorSMS works out the phone number itself and shows the draft to the owner, who edits it and presses Send. */
object HandoffWire {
    const val FROM_ARCHIVE = "message.draft.from_archive"
    const val STATUS = "message.draft.status"
    val OPS: Set<String> = setOf(FROM_ARCHIVE, STATUS)
}

/** One call to VictorSMS's archive ops. The Android implementation goes through the paired-node controller. */
interface ArchiveChannel {
    fun call(operation: String, params: JsonObject): ArchiveAnswer
}

sealed class ArchiveAnswer {
    data class Ok(val result: JsonObject) : ArchiveAnswer()

    /** VictorSMS was reached and said no. [code] is its machine code (`permission_denied`, `not_found`, ...). */
    data class Refused(val code: String, val message: String) : ArchiveAnswer()

    /** No trusted VictorSMS could be reached (not installed, not paired, certificate changed, no reply in time). */
    data class Unavailable(val message: String) : ArchiveAnswer()

    data class Failed(val message: String) : ArchiveAnswer()
}

class VictorSmsCommsBackend(
    private val channel: ArchiveChannel,
    private val clock: () -> Long = System::currentTimeMillis,
    private val okTtlMs: Long = 30_000,
    private val failTtlMs: Long = 5_000,
    /** The hand-off to VictorSMS ([HandoffWire]). Null means it is not wired: then those capabilities stay unavailable. */
    private val handoff: ArchiveChannel? = null,
) : CommsBackend {
    private val served = SERVED

    private class Probe(val at: Long, val ttl: Long, val reason: String?, val ops: Set<String>)

    private var probe: Probe? = null

    @Synchronized
    private fun probe(): Probe {
        val now = clock()
        probe?.takeIf { now - it.at in 0 until it.ttl }?.let { return it }
        val fresh = when (val a = channel.call(ArchiveWire.CAPABILITIES, JsonObject(emptyMap()))) {
            is ArchiveAnswer.Ok -> {
                val version = (a.result["version"] as? JsonPrimitive)?.longOrNull
                val ops = (a.result["ops"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                if (version != ArchiveWire.VERSION.toLong()) {
                    Probe(now, failTtlMs, "VictorSMS speaks archive protocol $version; this app speaks ${ArchiveWire.VERSION}. Update the older app.", emptySet())
                } else {
                    Probe(now, okTtlMs, null, ops)
                }
            }
            is ArchiveAnswer.Refused -> Probe(now, failTtlMs, explain(a.code, a.message), emptySet())
            is ArchiveAnswer.Unavailable -> Probe(now, failTtlMs, a.message, emptySet())
            is ArchiveAnswer.Failed -> Probe(now, failTtlMs, "VictorSMS did not answer: ${a.message}", emptySet())
        }
        probe = fresh
        return fresh
    }

    override fun availability(capability: String): String? {
        // The hand-off needs the same pairing and Archive read grant as the reads, so the same probe answers for it. The draft op
        // itself is not in the archive capability list; a VictorSMS that lacks it answers `unsupported_operation` when it is used.
        if (capability in HANDOFF_CAPABILITIES && handoff != null) return probe().reason
        val op = served[capability] ?: return NOT_PROVIDED
        val p = probe()
        p.reason?.let { return it }
        return if (op in p.ops) null else "This VictorSMS version does not offer $op. Update VictorSMS."
    }

    override fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult {
        if (capability in HANDOFF_CAPABILITIES && handoff != null) return handoffCall(handoff, capability, args, ctx)
        val op = served[capability] ?: return BackendResult.Refused("not_supported", NOT_PROVIDED)
        val spec = CommsCatalog.get(capability) ?: return BackendResult.Refused("not_supported", NOT_PROVIDED)
        val params = buildJsonObject {
            args.forEach { (name, value) ->
                // Numbers travel as numbers; the broker has already validated them as non-negative integers.
                val n = if (spec.args[name]?.type == "int") value.toLongOrNull() else null
                if (n != null) put(name, n) else put(name, value)
            }
        }
        return when (val a = channel.call(op, params)) {
            is ArchiveAnswer.Ok -> BackendResult.Ok(a.result.toString())
            is ArchiveAnswer.Refused -> BackendResult.Refused(a.code, explain(a.code, a.message))
            is ArchiveAnswer.Unavailable -> BackendResult.Refused("backend_unavailable", a.message)
            is ArchiveAnswer.Failed -> BackendResult.Failed(a.message)
        }
    }

    /**
     * Texting and replying go by CONVERSATION. VictorSMS derives the destination number itself and only ever creates a DRAFT for
     * the owner to review and send, so nothing here sends and no phone number is accepted or learned. The draft id comes from the
     * request id, so a retry of the same request is the same draft (VictorSMS is idempotent per draft id).
     */
    private fun handoffCall(ch: ArchiveChannel, capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult {
        if (capability == "communications.get_delivery_status") {
            val id = args["message_id"].orEmpty()
            if (!DRAFT_ID.matches(id)) return BackendResult.Refused("invalid_parameters", "That is not a draft id this connector issued.")
            return when (val a = ch.call(HandoffWire.STATUS, buildJsonObject { put("draftId", id) })) {
                is ArchiveAnswer.Ok -> {
                    val state = (a.result["state"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    BackendResult.Ok(
                        buildJsonObject {
                            put("draft_id", id)
                            put("state", plainState(state))
                            put("detail", plainDetail(state))
                            put("delivery_confirmed", false)
                        }.toString(),
                    )
                }
                else -> handoffFailure(a, statusCheck = true)
            }
        }
        val conversation = args["conversation"].orEmpty()
        if (args.containsKey("to") || conversation.isBlank()) {
            return BackendResult.Refused("send_by_conversation", "Texts go out by conversation. A phone number is not accepted: VictorSMS finds the number itself.")
        }
        val draftId = draftIdFor(ctx.requestId)
        val params = buildJsonObject {
            put("draftId", draftId)
            put("conversation", conversation)
            put("body", args["body"].orEmpty())
        }
        return when (val a = ch.call(HandoffWire.FROM_ARCHIVE, params)) {
            is ArchiveAnswer.Ok -> {
                val state = (a.result["state"] as? JsonPrimitive)?.contentOrNull ?: "received"
                BackendResult.Ok(
                    buildJsonObject {
                        put("draft_id", draftId)
                        put("state", plainState(state))
                        put("sent", false)
                        put("awaiting_owner", state == "received")
                        if (capability == "communications.send_sms") put("queued_for_owner", true)
                        put("note", "A draft is waiting in VictorSMS. Nothing is sent until the owner reviews it and presses Send.")
                    }.toString(),
                )
            }
            else -> handoffFailure(a)
        }
    }

    private fun handoffFailure(a: ArchiveAnswer, statusCheck: Boolean = false): BackendResult = when (a) {
        // VictorSMS answers not_found to a status check for a draft it has no record of; for a new draft it means the conversation is not shared.
        is ArchiveAnswer.Refused -> BackendResult.Refused(
            a.code,
            if (statusCheck && a.code == "not_found") "VictorSMS has no record of that draft. It may have expired." else explainHandoff(a.code, a.message),
        )
        is ArchiveAnswer.Unavailable -> BackendResult.Refused("backend_unavailable", a.message)
        is ArchiveAnswer.Failed -> BackendResult.Failed(a.message)
        is ArchiveAnswer.Ok -> BackendResult.Failed("Unexpected reply from VictorSMS.")
    }

    private fun explainHandoff(code: String, message: String): String = when (code) {
        "permission_denied" -> "VictorSMS has not granted droidcommand-AI both the Drafts and the Archive read permission."
        "no_sms_recipient" -> "That is not a one-to-one text conversation with a phone number, so VictorSMS cannot work out where it would go."
        "unsupported_operation", "not_implemented" -> "This VictorSMS version cannot take drafts from the assistant. Update VictorSMS."
        "invalid_parameters" -> "VictorSMS did not accept the text (empty, over 1,600 characters, or a bad conversation id)."
        "expired" -> "The draft expired before it could be handed over."
        else -> explain(code, message)
    }

    private fun plainState(state: String): String = when (state) {
        "received" -> "awaiting_owner"
        "submitted" -> "submitted"
        "declined" -> "declined"
        "failed" -> "failed"
        "unknown" -> "unknown"
        else -> "unknown"
    }

    private fun plainDetail(state: String): String = when (state) {
        "received" -> "Waiting for the owner to review it in VictorSMS and press Send."
        "submitted" -> "The owner pressed Send and VictorSMS handed it to the phone's sender. Delivery to the recipient is not confirmed."
        "declined" -> "The owner declined it or it expired. Nothing was sent."
        "failed" -> "VictorSMS could not send it. Nothing reached the recipient."
        else -> "The send was interrupted. Check the conversation before sending again."
    }

    private fun draftIdFor(requestId: String): String {
        val clean = requestId.filter { it.isLetterOrDigit() || it in "._:-" }.take(80)
        return "dca-" + clean.ifEmpty { Hashes.sha256(requestId).take(32) }
    }

    override fun context(conversation: String, query: String, maxChars: Int): ConversationContext? {
        val params = buildJsonObject {
            put("conversation", conversation)
            put("query", query.take(MAX_QUERY))
            put("max_chars", maxChars.coerceIn(MIN_CONTEXT, MAX_CONTEXT).toLong())
        }
        val r = (channel.call(ArchiveWire.CONTEXT, params) as? ArchiveAnswer.Ok)?.result ?: return null
        return runCatching {
            ConversationContext(
                messages = (r["messages"] as JsonArray).map { el ->
                    val m = el as JsonObject
                    CtxMessage(
                        fromOwner = (m["from_owner"] as JsonPrimitive).booleanOrNull ?: false,
                        text = (m["text"] as JsonPrimitive).content,
                        ts = (m["ts"] as JsonPrimitive).longOrNull ?: 0L,
                        relevant = (m["relevant"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                },
                facts = (r["facts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                summary = (r["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                ownerExamples = (r["owner_examples"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }.getOrNull()
    }

    private fun explain(code: String, message: String): String = when (code) {
        "external_control_disabled" -> "VictorSMS has External control switched off."
        "not_paired" -> "VictorSMS has not approved droidcommand-AI. Approve it in VictorSMS under Connected apps."
        "permission_denied" -> "VictorSMS has not granted droidcommand-AI the Archive read permission."
        "unsupported_operation", "not_implemented" -> "This VictorSMS version has no archive connector. Update VictorSMS."
        "not_found" -> "No such conversation is shared with the assistant in VictorSMS."
        "invalid_parameters" -> message.ifBlank { "VictorSMS did not accept the request." }
        else -> "VictorSMS refused the request ($code)."
    }

    companion object {
        private val SERVED = mapOf(
            "communications.list_conversations" to ArchiveWire.LIST,
            "communications.read_conversation" to ArchiveWire.READ,
            "communications.search_messages" to ArchiveWire.SEARCH,
        )

        /** The capabilities this connector can ever serve. Everything else VictorSMS owns is reported unavailable. */
        val SERVED_CAPABILITIES: Set<String> = SERVED.keys

        /** Served only when the hand-off channel is wired, and then only as drafts for the owner to send. */
        val HANDOFF_CAPABILITIES: Set<String> = setOf("communications.create_draft", "communications.send_sms", "communications.get_delivery_status")
        private val DRAFT_ID = Regex("^[A-Za-z0-9._:-]{1,96}$")

        const val NOT_PROVIDED = "VictorSMS does not provide this through the assistant connector."
        private const val MAX_QUERY = 200
        private const val MIN_CONTEXT = 200
        private const val MAX_CONTEXT = 12_000
        val OPERATIONS: Set<String> = ArchiveWire.OPS
    }
}
