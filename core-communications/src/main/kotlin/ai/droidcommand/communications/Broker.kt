package ai.droidcommand.communications

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object CommsContract {
    const val NAME = "COMPANION_COMMS"
    const val ACTION = "ai.droidcommand.action.COMPANION_COMMS"
    const val MIN_VERSION = 1
    const val MAX_VERSION = 1
    const val MAX_REQUEST_CHARS = 64_000
    const val MAX_TTL_MS = 5 * 60_000L
    const val MAX_SKEW_MS = 120_000L
    const val MAX_RESULT_CHARS = 60_000
    const val TASK_DISCOVER = "discover"
    const val TASK_CALL = "call"
    const val TASK_RECEIPT = "receipt"
    internal val JSON = Json { ignoreUnknownKeys = true }
    val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,64}")
    val NONCE = Regex("[0-9a-f]{32}")
}

/** What a backend (VictorSMS, VictorBots) reports for one operation. DroidCommand never invents these. */
sealed class BackendResult {
    data class Ok(val json: String = "{}") : BackendResult()
    data class Running(val jobId: String, val json: String = "{}") : BackendResult()
    data class Refused(val code: String, val message: String) : BackendResult()
    data class Failed(val message: String) : BackendResult()

    /** The operation may or may not have happened (timeout after hand-off, process death). Never retried automatically. */
    data class Uncertain(val message: String) : BackendResult()
}

data class BackendContext(
    val caller: String,
    val botId: String?,
    val requestId: String,
    /** `none` (read/draft), `approved` (the owner approved this exact call) or `auto` (an auto-reply grant applies). */
    val autonomy: String,
    val approvalId: String?,
)

data class CtxMessage(val fromOwner: Boolean, val text: String, val ts: Long, val relevant: Boolean)

data class ConversationContext(val messages: List<CtxMessage>, val facts: List<String>, val summary: String?, val ownerExamples: List<String>)

/** The communications side. VictorSMS and VictorBots each implement their part; tests use fakes. */
interface CommsBackend {
    /** Null when [capability] can be served right now; otherwise a plain reason ("VictorSMS is not installed"). */
    fun availability(capability: String): String?

    fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult

    /** Budgeted context for one reply, retrieved from the authoritative archive. Null when unavailable. */
    fun context(conversation: String, query: String, maxChars: Int): ConversationContext?
}

/** Routes each capability to the app that owns it. A missing app makes its capabilities honestly unavailable. */
class CompositeBackend(private val parts: Map<Owner, CommsBackend>) : CommsBackend {
    private fun route(cap: String): CommsBackend? = CommsCatalog.get(cap)?.owner?.let { parts[it] }

    override fun availability(capability: String): String? =
        route(capability)?.availability(capability) ?: "The app that provides this (${CommsCatalog.get(capability)?.owner?.name?.lowercase()}) is not connected."

    override fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult =
        route(capability)?.call(capability, args, ctx) ?: BackendResult.Refused("backend_not_connected", "The app that provides this is not connected.")

    override fun context(conversation: String, query: String, maxChars: Int) = parts[Owner.VICTORSMS]?.context(conversation, query, maxChars)
}

private class Parsed(
    val task: String, val requestId: String, val nonce: String, val ts: Long, val expiresAt: Long, val capability: String?,
    val args: Map<String, String>, val botId: String?, val mode: String?, val reason: String, val v: Int, val minV: Int, val hash: String,
)

class CommsBroker(
    private val backend: CommsBackend,
    private val replies: ReplyService?,
    val grants: GrantStore,
    val approvals: ApprovalQueue,
    private val receipts: ReceiptStore,
    val kill: KillSwitch,
    private val audit: AuditLog?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val replay: ReplayGuard = ReplayGuard(clock),
    private val rate: RateLimiter = RateLimiter(clock),
    private val build: String = "dev",
) {
    private val authorizer = Authorizer(grants, clock)

    /**
     * Handles one request from a caller whose package and signing certificate the transport has ALREADY verified
     * (same catalogue and pairing as COMPANION_ASSIST). [caller] must come from that verification, never from the payload.
     */
    fun handle(caller: String, raw: String?): String {
        if (caller.isBlank()) return err("not_paired", "Caller identity is required.")
        val p = when (val r = parse(raw)) {
            is Either.Bad -> return r.json
            is Either.Good -> r.value
        }
        val negotiated = minOf(p.v, CommsContract.MAX_VERSION)
        if (negotiated < maxOf(p.minV, CommsContract.MIN_VERSION)) {
            return err("incompatible_version", "This droidcommand-AI speaks COMPANION_COMMS v${CommsContract.MIN_VERSION}-${CommsContract.MAX_VERSION}; you require v${p.minV}+.", requestId = p.requestId)
        }
        return when (p.task) {
            CommsContract.TASK_DISCOVER -> discover(negotiated)
            CommsContract.TASK_RECEIPT -> receipts.get(caller, p.requestId)?.let(::receiptJson) ?: unknownReceipt(p.requestId)
            else -> call(caller, p, local = false)
        }
    }

    /** DroidCommand's own agent (Chat/Plan/Pilot/Forge) calling a capability. The owner's confirmation happened in the tool executor. */
    fun local(capability: String, args: Map<String, String>, botId: String? = null): String {
        val id = "local-" + Hashes.sha256("$capability${clock()}${System.nanoTime()}").take(24)
        val p = Parsed(CommsContract.TASK_CALL, id, "0".repeat(32), clock(), clock() + 60_000, capability, args, botId, null, "local", 1, 1, Hashes.sha256(capability + args.toSortedMap()))
        return call(LOCAL_CALLER, p, local = true)
    }

    fun discover(version: Int = CommsContract.MAX_VERSION): String = CommsContract.JSON.encodeToString(JsonObject.serializer(), discoverObject(version))

    private fun discoverObject(version: Int): JsonObject = obj {
        put("v", version)
        put("ok", true)
        put("contract", CommsContract.NAME)
        put(
            "versions",
            obj {
                put("min", CommsContract.MIN_VERSION)
                put("max", CommsContract.MAX_VERSION)
            },
        )
        put("build", build)
        put("kill_switch", kill.engaged)
        put("approval", "Sends and calls need the owner's approval for every call unless an auto-reply grant exists for that bot and contact. Grants are created only by the owner in droidcommand-AI.")
        put(
            "limits",
            obj {
                put("max_request_chars", CommsContract.MAX_REQUEST_CHARS)
                put("max_ttl_ms", CommsContract.MAX_TTL_MS)
                put("max_result_chars", CommsContract.MAX_RESULT_CHARS)
            },
        )
        put(
            "capabilities",
            JsonArray(
                CommsCatalog.ALL.map { c ->
                    val reason = availabilityOf(c)
                    obj {
                        put("id", c.id)
                        put("since", c.since)
                        put("description", c.description)
                        put("risk", c.risk.name.lowercase())
                        put("owner", c.owner.name.lowercase())
                        put("status", if (reason == null) "available" else "unavailable")
                        reason?.let { put("reason", it) }
                        put("needs", JsonArray(c.permissions.map { JsonPrimitive(it.name.lowercase()) }))
                        put("approval", c.approval.name.lowercase())
                        put(
                            "args",
                            obj {
                                c.args.forEach { (k, a) ->
                                    put(
                                        k,
                                        obj {
                                            put("type", a.type)
                                            put("required", a.required)
                                            put("max", a.max)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            ),
        )
    }

    /** Null when the capability can run now. AI capabilities also need a model route and the conversation archive. */
    private fun availabilityOf(c: CommsCapability): String? = when (c.id) {
        "communications.generate_reply", "communications.summarize_conversation" ->
            if (replies == null) "No AI model route is configured in droidcommand-AI." else backend.availability("communications.read_conversation")
        else -> backend.availability(c.id)
    }

    private fun call(caller: String, p: Parsed, local: Boolean): String {
        val rid = p.requestId
        // 1. Idempotency: a request id is executed at most once, and only for the payload it was first used with.
        receipts.get(caller, rid)?.let { r -> return if (r.payloadHash != p.hash) err("request_id_reused", "This request id was already used for a different request.", rid) else receiptJson(r) }
        val now = clock()
        if (!local) {
            if (p.expiresAt <= now) return err("expired", "The request expired before it was processed.", rid)
            if (p.expiresAt - now > CommsContract.MAX_TTL_MS) return err("invalid_request", "expires_at is more than 5 minutes away.", rid)
            if (kotlin.math.abs(now - p.ts) > CommsContract.MAX_SKEW_MS) return err("stale_timestamp", "The request timestamp is outside the allowed clock skew.", rid)
            if (replay.predatesStart(p.ts)) return err("stale_timestamp", "droidcommand-AI restarted after this request was stamped, so it cannot be checked against replays. Send it again with a fresh timestamp and nonce.", rid)
            if (!replay.firstUse(caller, p.nonce)) {
                audit(AuditEventType.ACCESS_DENIED, caller, "replayed nonce for $rid")
                return err("replayed_nonce", "This nonce was already used; send a fresh one.", rid)
            }
        }
        val cap = p.capability?.let { CommsCatalog.get(it) } ?: return err("unknown_capability", "Unknown capability. Call discover.", rid)
        availabilityOf(cap)?.let { reason -> return refuse(caller, p, cap, "unavailable", reason, record = false) }
        CommsCatalog.validate(cap, p.args).takeIf { it.isNotEmpty() }?.let { return err("invalid_args", it.joinToString(" "), rid) }
        if (kill.engaged && cap.risk in setOf(CommsRisk.SEND, CommsRisk.CALL, CommsRisk.ADMIN) && cap.safeWhen?.invoke(p.args) != true) {
            audit(AuditEventType.BOT_MESSAGE_DENIED, caller, "${cap.id} blocked by the emergency stop")
            return refuse(caller, p, cap, "kill_switch", "All automatic communications are stopped by the owner.")
        }
        if (!receipts.healthy && cap.risk in setOf(CommsRisk.SEND, CommsRisk.CALL)) {
            return refuse(caller, p, cap, "receipts_unavailable", "Stored receipts could not be read, so a send could be repeated. Nothing was sent.", record = false)
        }
        if (!rate.allow(caller, cap.risk)) return refuse(caller, p, cap, "rate_limited", "Too many ${cap.risk.name.lowercase()} requests; slow down.", record = false)
        if (cap.id == "communications.cancel_job" && receipts.byJob(caller, p.args.getValue("job_id")) == null) {
            return refuse(caller, p, cap, "not_your_job", "Only the app that started a job can cancel it.")
        }

        // 2. Authorisation: grants the owner created, never the model's say-so.
        val targets = CommsCatalog.targets(p.args)
        var autonomy = "none"
        var grantIds: List<String> = emptyList()
        if (!local && cap.needsGrant) {
            when (val d = authorizer.check(caller, p.botId, targets, cap.permissions)) {
                is AuthDecision.Denied -> {
                    audit(AuditEventType.ACCESS_DENIED, caller, "${cap.id} lacks ${d.missing.joinToString { it.name }}")
                    return refuse(caller, p, cap, "not_granted", "Not granted: ${d.missing.joinToString { it.name.lowercase() }}.", record = false, extra = { put("missing", JsonArray(d.missing.map { JsonPrimitive(it.name.lowercase()) })) })
                }
                is AuthDecision.Allowed -> {
                    grantIds = d.grantIds
                    if (d.autoReply && p.mode == "auto") autonomy = "auto"
                }
            }
        }

        // 3. Approval: per call, bound to the exact arguments. A standing grant does not replace it unless auto-reply was granted.
        var approvalId: String? = null
        val needsApproval = !local && when (cap.approval) {
            Approval.NEVER -> false
            Approval.PER_CALL -> autonomy != "auto"
            Approval.OWNER_EVERY_TIME -> cap.safeWhen?.invoke(p.args) != true
        }
        if (needsApproval) {
            val item = approvals.submit(caller, rid, p.hash, cap.id, summarize(cap, p.args), p.botId, p.expiresAt)
            if (item.payloadHash != p.hash) return err("request_id_reused", "This request id has a pending approval for a different request.", rid)
            when (item.state) {
                ApprovalState.PENDING -> {
                    audit(AuditEventType.APPROVAL_UNAVAILABLE, caller, "${cap.id} waiting for the owner (${item.id})")
                    return pending(rid, item.id)
                }
                ApprovalState.DENIED -> {
                    audit(AuditEventType.APPROVAL_DENIED, caller, "${cap.id} denied by the owner")
                    return refuse(caller, p, cap, "denied", "The owner denied this request. Nothing was done.")
                }
                ApprovalState.EXPIRED -> {
                    audit(AuditEventType.APPROVAL_TIMED_OUT, caller, "${cap.id} approval expired")
                    return refuse(caller, p, cap, "approval_expired", "The approval expired before the request ran. Nothing was done.")
                }
                ApprovalState.USED -> return err("approval_used", "That approval was already used.", rid)
                ApprovalState.APPROVED -> {
                    if (!approvals.consume(item.id)) return err("approval_used", "That approval was already used.", rid)
                    approvalId = item.id
                    autonomy = "approved"
                    audit(AuditEventType.APPROVAL_APPROVED, caller, "${cap.id} approved by the owner (${item.id})")
                }
            }
        }

        // 4. Execution. Anything that sends, calls or changes automation must be audited first: a full log fails closed.
        if (audit != null && cap.risk in setOf(CommsRisk.SEND, CommsRisk.CALL, CommsRisk.ADMIN) &&
            !audit.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "companion_comms:$caller", "executing ${cap.id} request $rid"))
        ) {
            return refuse(caller, p, cap, "audit_unavailable", "The audit log could not record this, so it was not run.")
        }
        val result: BackendResult = try {
            when (cap.id) {
                "communications.generate_reply" -> replies!!.generateReply(p.args, p.botId, backend)
                "communications.summarize_conversation" -> replies!!.summarize(p.args, backend)
                else -> backend.call(cap.id, p.args, BackendContext(caller, p.botId, rid, autonomy, approvalId))
            }
        } catch (e: Exception) {
            // After hand-off we cannot know. For anything that sends or calls, uncertainty is the honest answer.
            if (cap.risk == CommsRisk.SEND || cap.risk == CommsRisk.CALL) {
                BackendResult.Uncertain("Lost contact while sending (${e.javaClass.simpleName}); it may or may not have gone out.")
            } else {
                BackendResult.Failed("The operation failed (${e.javaClass.simpleName}).")
            }
        }
        if (result !is BackendResult.Refused) authorizer.consume(grantIds)
        val receipt = when (result) {
            is BackendResult.Ok -> mk(caller, p, cap, CommsState.COMPLETED, "ok", "", result.json)
            is BackendResult.Running -> mk(caller, p, cap, CommsState.RUNNING, "running", "", result.json, result.jobId)
            is BackendResult.Refused -> mk(caller, p, cap, CommsState.REFUSED, result.code, result.message, "{}")
            is BackendResult.Failed -> mk(caller, p, cap, CommsState.FAILED, "failed", result.message, "{}")
            is BackendResult.Uncertain -> mk(caller, p, cap, CommsState.UNKNOWN, "outcome_unknown", result.message, "{}")
        }
        val stored = receipts.putIfAbsent(receipt)
        audit(if (stored.state == CommsState.COMPLETED || stored.state == CommsState.RUNNING) AuditEventType.ACCESS_GRANTED else AuditEventType.ACCESS_DENIED, caller, "${cap.id} -> ${stored.state.name.lowercase()} (${stored.code}) request $rid")
        return receiptJson(stored)
    }

    // ---- request parsing ----

    private sealed class Either<out T> {
        class Good<T>(val value: T) : Either<T>()
        class Bad(val json: String) : Either<Nothing>()
    }

    private fun parse(raw: String?): Either<Parsed> {
        if (raw.isNullOrBlank()) return Either.Bad(err("invalid_request", "Empty request."))
        if (raw.length > CommsContract.MAX_REQUEST_CHARS) return Either.Bad(err("invalid_request", "Request is too large."))
        val o = try {
            CommsContract.JSON.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Either.Bad(err("invalid_request", "Request must be a JSON object."))
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val v = (o["v"] as? JsonPrimitive)?.intOrNull ?: return Either.Bad(err("invalid_request", "Missing protocol version 'v'."))
        val minV = (o["min_v"] as? JsonPrimitive)?.intOrNull ?: v
        val rid = str("request_id")?.takeIf { CommsContract.REQUEST_ID.matches(it) } ?: return Either.Bad(err("invalid_request", "request_id must be 8-64 characters of [A-Za-z0-9_-]."))
        val task = str("task") ?: return Either.Bad(err("invalid_request", "Missing task.", rid))
        if (task !in setOf(CommsContract.TASK_DISCOVER, CommsContract.TASK_CALL, CommsContract.TASK_RECEIPT)) return Either.Bad(err("invalid_request", "task must be discover, call or receipt.", rid))
        if (task == CommsContract.TASK_DISCOVER || task == CommsContract.TASK_RECEIPT) {
            return Either.Good(Parsed(task, rid, "", 0, 0, null, emptyMap(), null, null, "", v, minV, ""))
        }
        val nonce = str("nonce")?.takeIf { CommsContract.NONCE.matches(it) } ?: return Either.Bad(err("invalid_request", "nonce must be 32 lowercase hex characters.", rid))
        val ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: return Either.Bad(err("invalid_request", "Missing ts.", rid))
        val exp = (o["expires_at"] as? JsonPrimitive)?.longOrNull ?: return Either.Bad(err("invalid_request", "Missing expires_at.", rid))
        val args = linkedMapOf<String, String>()
        (o["args"] as? JsonObject)?.forEach { (k, el) ->
            val s = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { prim -> prim.booleanOrNull?.toString() ?: prim.content }
                ?: return Either.Bad(err("invalid_args", "Argument '$k' must be a string, number or boolean.", rid))
            if (k.length > 40 || args.size >= 16) return Either.Bad(err("invalid_args", "Too many or too long argument names.", rid))
            args[k] = s
        }
        val actor = o["actor"] as? JsonObject
        val bot = (actor?.get("bot_id") as? JsonPrimitive)?.contentOrNull
        val mode = (actor?.get("mode") as? JsonPrimitive)?.contentOrNull
        val cap = str("capability")
        val hash = Hashes.sha256("$cap|${args.toSortedMap()}|$bot|$mode")
        return Either.Good(Parsed(task, rid, nonce, ts, exp, cap, args, bot, mode, (str("reason") ?: "").take(200), v, minV, hash))
    }

    // ---- responses ----

    private fun obj(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(b)

    private fun err(code: String, message: String, requestId: String? = null): String = CommsContract.JSON.encodeToString(
        JsonObject.serializer(),
        obj {
            put("v", CommsContract.MAX_VERSION)
            put("ok", false)
            put("state", "error")
            put("code", code)
            put("detail", message)
            requestId?.let { put("request_id", it) }
            put(
                "versions",
                obj {
                    put("min", CommsContract.MIN_VERSION)
                    put("max", CommsContract.MAX_VERSION)
                },
            )
        },
    )

    private fun pending(rid: String, approvalId: String): String = CommsContract.JSON.encodeToString(
        JsonObject.serializer(),
        obj {
            put("v", CommsContract.MAX_VERSION)
            put("ok", true)
            put("state", "pending_approval")
            put("request_id", rid)
            put("approval_id", approvalId)
            put("detail", "Waiting for the owner to approve in droidcommand-AI. Nothing has been done. Send the same request again (same request_id and arguments, fresh nonce) to collect the outcome.")
        },
    )

    private fun refuse(caller: String, p: Parsed, cap: CommsCapability, code: String, message: String, record: Boolean = true, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null): String {
        if (record) {
            val r = receipts.putIfAbsent(mk(caller, p, cap, CommsState.REFUSED, code, message, "{}"))
            return receiptJson(r)
        }
        return CommsContract.JSON.encodeToString(
            JsonObject.serializer(),
            obj {
                put("v", CommsContract.MAX_VERSION)
                put("ok", false)
                put("state", "refused")
                put("request_id", p.requestId)
                put("code", code)
                put("detail", message)
                extra?.invoke(this)
            },
        )
    }

    private fun mk(caller: String, p: Parsed, cap: CommsCapability, state: CommsState, code: String, detail: String, json: String, job: String? = null) =
        Receipt(caller, p.requestId, p.hash, cap.id, state, code, detail.take(300), json.take(CommsContract.MAX_RESULT_CHARS), job, clock())

    private fun receiptJson(r: Receipt): String = CommsContract.JSON.encodeToString(
        JsonObject.serializer(),
        obj {
            put("v", CommsContract.MAX_VERSION)
            put("ok", r.state == CommsState.COMPLETED || r.state == CommsState.RUNNING)
            put("state", r.state.name.lowercase())
            put("request_id", r.requestId)
            put("capability", r.capability)
            put("code", r.code)
            put("detail", r.detail)
            put("finished_at", r.finishedAtMs)
            r.jobId?.let { put("job_id", it) }
            put("result", runCatching { CommsContract.JSON.parseToJsonElement(r.resultJson) }.getOrDefault(JsonObject(emptyMap())))
            put("audit_ref", "audit:${r.requestId}")
        },
    )

    private fun unknownReceipt(rid: String) = CommsContract.JSON.encodeToString(
        JsonObject.serializer(),
        obj {
            put("v", CommsContract.MAX_VERSION)
            put("ok", true)
            put("known", false)
            put("request_id", rid)
            put("detail", "No request with this id was recorded for this caller. Nothing ran for it.")
        },
    )

    private fun summarize(cap: CommsCapability, args: Map<String, String>): String =
        cap.id.removePrefix("communications.") + ": " + args.filterKeys { it != "attachment_ref" }.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }

    private fun audit(type: AuditEventType, caller: String, detail: String) {
        audit?.record(AuditEvent(type, "companion_comms:$caller", detail))
    }

    companion object {
        const val LOCAL_CALLER = "droidcommand-ai:local"
    }
}
