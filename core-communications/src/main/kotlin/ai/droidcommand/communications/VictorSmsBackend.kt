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
        val op = served[capability] ?: return NOT_PROVIDED
        val p = probe()
        p.reason?.let { return it }
        return if (op in p.ops) null else "This VictorSMS version does not offer $op. Update VictorSMS."
    }

    override fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult {
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

        const val NOT_PROVIDED = "VictorSMS does not provide this through the assistant connector."
        private const val MAX_QUERY = 200
        private const val MIN_CONTEXT = 200
        private const val MAX_CONTEXT = 12_000
        val OPERATIONS: Set<String> = ArchiveWire.OPS
    }
}
