package ai.droidcommand.security

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * COMPANION_EXEC v1: a separate, versioned contract for a paired fleet app to ask droidcommand-AI to
 * run ONE catalogued capability, after the owner taps Allow on a droidcommand-AI screen.
 *
 * It is deliberately not COMPANION_ASSIST (which stays text-only and never runs tools). Properties:
 *  - Caller identity and signing cert are checked with the same catalogue/pairing as COMPANION_ASSIST;
 *    there is no second allow-list. Missing identity fails closed.
 *  - `discover` lists what this build can run for that caller. It runs nothing.
 *  - `execute` carries a caller-chosen `request_id` and an `expires_at`. Authorization is scoped to that
 *    one request: the owner's Allow tap (in this app, never in the caller) is the only approval, and
 *    it is bound to the exact capability + arguments shown. There are no standing grants.
 *  - Execution goes through the existing [ToolRunner] (SecureToolExecutor), so the policy enforcer,
 *    its own approval prompt, grant checks and the audit log still apply on top of the tap.
 *  - A genuine [ExecReceipt] is recorded, keyed by (caller, request_id). A repeated `execute` with the
 *    same id returns the stored receipt and never runs again. `receipt` lets a caller that crashed
 *    find out what happened, without re-running anything.
 *  - Cancellation: before the tap, Deny/back = `denied` (nothing ran). Once a tool has started it is
 *    short and bounded and cannot be cancelled by the caller; the receipt reports the true outcome.
 *  - Only capabilities in [ExecCatalog.DEFAULT] exist; the phone cannot name a tool or pass free-form
 *    commands. Arguments are validated per capability.
 */
object CompanionExec {
    const val ACTION = "ai.droidcommand.action.COMPANION_EXEC"
    const val EXTRA_REQUEST_JSON = "ai.droidcommand.extra.REQUEST_JSON"
    const val EXTRA_RESULT_JSON = "ai.droidcommand.extra.RESULT_JSON"
    const val CONTRACT_VERSION = 1
    const val TASK_DISCOVER = "discover"
    const val TASK_EXECUTE = "execute"
    const val TASK_RECEIPT = "receipt"

    /** A request must expire within this window of "now"; longer lifetimes are refused. */
    const val MAX_TTL_MS = 5 * 60_000L
    const val MAX_OUTPUT_CHARS = 8_000
    const val MAX_REQUEST_CHARS = 8_000

    internal val JSON = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?, nowMs: Long): ExecParse {
        if (raw.isNullOrBlank()) return ExecParse.Invalid("Missing $EXTRA_REQUEST_JSON.")
        if (raw.length > MAX_REQUEST_CHARS) return ExecParse.Invalid("REQUEST_JSON is too large.")
        val obj = try {
            JSON.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return ExecParse.Invalid("REQUEST_JSON must be a JSON object.")
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != CONTRACT_VERSION) {
            return ExecParse.Invalid("Unsupported COMPANION_EXEC contract version.")
        }
        val requestId = (obj["request_id"] as? JsonPrimitive)?.contentOrNull
        if (requestId == null || !REQUEST_ID.matches(requestId)) {
            return ExecParse.Invalid("request_id must be 8-64 characters of [A-Za-z0-9_-].")
        }
        return when ((obj["task"] as? JsonPrimitive)?.contentOrNull) {
            TASK_DISCOVER -> ExecParse.Valid(ExecRequest.Discover(requestId))
            TASK_RECEIPT -> ExecParse.Valid(ExecRequest.Receipt(requestId))
            TASK_EXECUTE -> {
                val cap = (obj["capability"] as? JsonPrimitive)?.contentOrNull
                    ?: return ExecParse.Invalid("capability is required.")
                val expires = (obj["expires_at"] as? JsonPrimitive)?.longOrNull
                    ?: return ExecParse.Invalid("expires_at (epoch ms) is required.")
                if (expires <= nowMs) return ExecParse.Invalid("This request has already expired.")
                if (expires - nowMs > MAX_TTL_MS) {
                    return ExecParse.Invalid("expires_at is more than ${MAX_TTL_MS / 60_000} minutes away.")
                }
                val args = linkedMapOf<String, String>()
                (obj["args"] as? JsonObject)?.forEach { (k, v) ->
                    val s = (v as? JsonPrimitive)?.contentOrNull
                        ?: return ExecParse.Invalid("args values must be strings or numbers.")
                    if (k.length > 40 || s.length > 500 || args.size >= 8) return ExecParse.Invalid("args are too large.")
                    args[k] = s
                }
                val reason = ((obj["reason"] as? JsonPrimitive)?.contentOrNull ?: "").take(200)
                ExecParse.Valid(ExecRequest.Execute(requestId, cap, args, expires, reason))
            }
            else -> ExecParse.Invalid("task must be discover, execute or receipt.")
        }
    }

    private val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,64}")

    fun errorJson(message: String): String = JSON.encodeToString(
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(CONTRACT_VERSION),
                "ok" to JsonPrimitive(false),
                "error" to JsonPrimitive(message),
            ),
        ),
    )
}

sealed class ExecParse {
    data class Valid(val request: ExecRequest) : ExecParse()
    data class Invalid(val reason: String) : ExecParse()
}

sealed class ExecRequest {
    abstract val requestId: String
    data class Discover(override val requestId: String) : ExecRequest()
    data class Receipt(override val requestId: String) : ExecRequest()
    data class Execute(
        override val requestId: String,
        val capability: String,
        val args: Map<String, String>,
        val expiresAtMs: Long,
        val reason: String,
    ) : ExecRequest()
}

/** One thing this build can run, mapped to an existing registered tool. Nothing else is reachable. */
data class ExecCapability(
    val id: String,
    val toolName: String,
    val description: String,
    val risk: RiskTier,
    val allowedArgs: Set<String> = emptySet(),
)

class ExecCatalog(private val entries: List<ExecCapability>) {
    fun get(id: String): ExecCapability? = entries.firstOrNull { it.id == id }
    fun all(): List<ExecCapability> = entries

    companion object {
        /**
         * Read-only by design. Anything that changes the device is a later, separately reviewed addition
         * to this list; it is not reachable through arguments.
         */
        val DEFAULT = ExecCatalog(
            listOf(
                ExecCapability("device.apps.list", "list_installed_apps", "List installed apps (names and package ids)", RiskTier.READ_ONLY),
            ),
        )
    }
}

enum class ExecOutcome { EXECUTED, DENIED, FAILED, EXPIRED, REFUSED }

/** What genuinely happened. `executed` is only ever set from a successful [ToolResult]. */
data class ExecReceipt(
    val requestId: String,
    val caller: String,
    val capability: String,
    val outcome: ExecOutcome,
    val detail: String,
    val output: String,
    val finishedAtMs: Long,
    val auditNote: String,
)

interface ExecReceiptStore {
    fun get(caller: String, requestId: String): ExecReceipt?

    /** Stores the receipt only if none exists for (caller, requestId). @return the winner. */
    fun putIfAbsent(receipt: ExecReceipt): ExecReceipt
}

class InMemoryExecReceiptStore : ExecReceiptStore {
    private val map = linkedMapOf<String, ExecReceipt>()

    @Synchronized
    override fun get(caller: String, requestId: String) = map["$caller|$requestId"]

    @Synchronized
    override fun putIfAbsent(receipt: ExecReceipt): ExecReceipt = map.getOrPut("${receipt.caller}|${receipt.requestId}") { receipt }
}

/** One JSON object per line; survives a process restart so a caller can reconcile afterwards. */
class JsonFileExecReceiptStore(private val file: Path, private val capacity: Int = 2_000) : ExecReceiptStore {
    private val lock = Any()

    init {
        file.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(file)) Files.write(file, ByteArray(0), StandardOpenOption.CREATE)
    }

    private fun read(): List<ExecReceipt> = Files.readAllLines(file).filter { it.isNotBlank() }.mapNotNull { line ->
        try {
            val o = CompanionExec.JSON.parseToJsonElement(line) as JsonObject
            fun s(k: String) = (o[k] as JsonPrimitive).content
            ExecReceipt(s("request_id"), s("caller"), s("capability"), ExecOutcome.valueOf(s("outcome")), s("detail"), s("output"), s("finished_at").toLong(), s("audit_note"))
        } catch (_: Exception) {
            null
        }
    }

    override fun get(caller: String, requestId: String): ExecReceipt? =
        synchronized(lock) { read().firstOrNull { it.caller == caller && it.requestId == requestId } }

    override fun putIfAbsent(receipt: ExecReceipt): ExecReceipt = synchronized(lock) {
        val all = read()
        all.firstOrNull { it.caller == receipt.caller && it.requestId == receipt.requestId }?.let { return it }
        if (all.size >= capacity) {
            // Fail closed rather than silently drop evidence of what ran.
            return receipt.copy(outcome = ExecOutcome.REFUSED, detail = "Receipt store is full; nothing was recorded.")
        }
        val line = JsonObject(
            mapOf(
                "request_id" to JsonPrimitive(receipt.requestId),
                "caller" to JsonPrimitive(receipt.caller),
                "capability" to JsonPrimitive(receipt.capability),
                "outcome" to JsonPrimitive(receipt.outcome.name),
                "detail" to JsonPrimitive(receipt.detail),
                "output" to JsonPrimitive(receipt.output),
                "finished_at" to JsonPrimitive(receipt.finishedAtMs.toString()),
                "audit_note" to JsonPrimitive(receipt.auditNote),
            ),
        )
        Files.write(file, (CompanionExec.JSON.encodeToString(JsonObject.serializer(), line) + "\n").toByteArray(), StandardOpenOption.APPEND)
        receipt
    }
}

/** What the consent screen must show, and exactly what will run if the owner taps Allow. */
data class ExecConsent(
    val caller: String,
    val capability: ExecCapability,
    val args: Map<String, String>,
    val reason: String,
    val expiresAtMs: Long,
    val request: ExecRequest.Execute,
)

sealed class ExecPrepared {
    data class NeedsConsent(val consent: ExecConsent) : ExecPrepared()
    data class Done(val receipt: ExecReceipt) : ExecPrepared()
    data class Rejected(val reason: String) : ExecPrepared()
}

/**
 * The broker is pure JVM: the Activity supplies the verified caller, the clock, and the owner's tap.
 * [callerVerified] must come from [CompanionAssist.verifyCaller]; the broker refuses an empty caller.
 */
class ExecBroker(
    private val catalog: ExecCatalog,
    private val runner: ToolRunner,
    private val receipts: ExecReceiptStore,
    private val auditLog: AuditLog?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun discover(caller: String): String {
        require(caller.isNotBlank()) { "caller identity is required" }
        return CompanionExec.JSON.encodeToString(
            buildJsonObject {
                put("v", CompanionExec.CONTRACT_VERSION)
                put("ok", true)
                put("contract", "COMPANION_EXEC")
                put("approval", "owner taps Allow in droidcommand-AI for every execute request; no standing grants")
                put("expires_max_ms", CompanionExec.MAX_TTL_MS)
                put(
                    "capabilities",
                    JsonArray(
                        catalog.all().map {
                            buildJsonObject {
                                put("id", it.id)
                                put("description", it.description)
                                put("risk", it.risk.name)
                                put("args", JsonArray(it.allowedArgs.map { a -> JsonPrimitive(a) }))
                            }
                        },
                    ),
                )
            },
        )
    }

    fun receiptJson(caller: String, requestId: String): String {
        val r = receipts.get(caller, requestId)
        return if (r == null) {
            CompanionExec.JSON.encodeToString(
                buildJsonObject {
                    put("v", CompanionExec.CONTRACT_VERSION)
                    put("ok", true)
                    put("known", false)
                    put("note", "No request with this id was recorded for this caller. Nothing ran for it.")
                },
            )
        } else {
            receiptToJson(r)
        }
    }

    fun prepare(caller: String, req: ExecRequest.Execute): ExecPrepared {
        if (caller.isBlank()) return ExecPrepared.Rejected("caller identity is required")
        receipts.get(caller, req.requestId)?.let { return ExecPrepared.Done(it) } // never runs twice
        val cap = catalog.get(req.capability)
        if (cap == null) {
            audit(AuditEventType.ACCESS_DENIED, caller, "unknown capability '${req.capability}'")
            return ExecPrepared.Rejected("Unknown capability. Call discover to see what this build offers.")
        }
        val extra = req.args.keys - cap.allowedArgs
        if (extra.isNotEmpty()) {
            audit(AuditEventType.ACCESS_DENIED, caller, "arguments not allowed for ${cap.id}: $extra")
            return ExecPrepared.Rejected("Arguments not allowed for ${cap.id}: ${extra.joinToString()}")
        }
        if (req.expiresAtMs <= clock()) return ExecPrepared.Done(record(caller, req, cap.id, ExecOutcome.EXPIRED, "The request expired before it was approved.", ""))
        return ExecPrepared.NeedsConsent(ExecConsent(caller, cap, req.args, req.reason, req.expiresAtMs, req))
    }

    /** Called after the owner's decision on the consent screen. [allowed] is false for Deny/back. */
    fun finish(consent: ExecConsent, allowed: Boolean, isCancelled: () -> Boolean = { false }): ExecReceipt {
        val req = consent.request
        receipts.get(consent.caller, req.requestId)?.let { return it }
        if (!allowed) {
            audit(AuditEventType.APPROVAL_DENIED, consent.caller, "COMPANION_EXEC ${consent.capability.id} denied by the owner")
            return record(consent.caller, req, consent.capability.id, ExecOutcome.DENIED, "The owner denied this request. Nothing ran.", "")
        }
        if (req.expiresAtMs <= clock()) {
            audit(AuditEventType.APPROVAL_TIMED_OUT, consent.caller, "COMPANION_EXEC ${consent.capability.id} expired before running")
            return record(consent.caller, req, consent.capability.id, ExecOutcome.EXPIRED, "The request expired before the owner approved it. Nothing ran.", "")
        }
        audit(AuditEventType.APPROVAL_APPROVED, consent.caller, "COMPANION_EXEC ${consent.capability.id} approved by the owner for request ${req.requestId}")
        val result = try {
            runner.run(
                toolName = consent.capability.toolName,
                input = consent.args,
                isCancelled = isCancelled,
                mode = null,
                initiator = Initiator.REMOTE,
            )
        } catch (e: Exception) {
            ToolResult.Failure("Tool threw: ${e.javaClass.simpleName}")
        }
        return when (result) {
            is ToolResult.Success -> record(consent.caller, req, consent.capability.id, ExecOutcome.EXECUTED, "The tool reported success.", result.output.toString())
            is ToolResult.Partial -> record(consent.caller, req, consent.capability.id, ExecOutcome.FAILED, "The tool only partly completed.", result.toString())
            is ToolResult.Failure -> record(consent.caller, req, consent.capability.id, ExecOutcome.FAILED, result.reason, "")
            else -> record(consent.caller, req, consent.capability.id, ExecOutcome.FAILED, "The tool returned an unexpected result.", "")
        }
    }

    /** The owner never answered before the request expired (e.g. the consent screen timed out). Nothing ran. */
    fun expire(consent: ExecConsent): ExecReceipt {
        val req = consent.request
        receipts.get(consent.caller, req.requestId)?.let { return it }
        audit(AuditEventType.APPROVAL_TIMED_OUT, consent.caller, "COMPANION_EXEC ${consent.capability.id} expired while waiting for the owner")
        return record(consent.caller, req, consent.capability.id, ExecOutcome.EXPIRED, "The request expired before the owner approved it. Nothing ran.", "")
    }

    private fun record(caller: String, req: ExecRequest.Execute, cap: String, outcome: ExecOutcome, detail: String, output: String): ExecReceipt {
        val r = ExecReceipt(req.requestId, caller, cap, outcome, detail.take(300), output.take(CompanionExec.MAX_OUTPUT_CHARS), clock(), "audit:${req.requestId}")
        return receipts.putIfAbsent(r)
    }

    private fun audit(type: AuditEventType, caller: String, detail: String) {
        auditLog?.record(AuditEvent(type, "companion_exec:$caller", detail))
    }

    companion object {
        fun receiptToJson(r: ExecReceipt): String = CompanionExec.JSON.encodeToString(
            buildJsonObject {
                put("v", CompanionExec.CONTRACT_VERSION)
                put("ok", true)
                put("known", true)
                put("request_id", r.requestId)
                put("capability", r.capability)
                put("outcome", r.outcome.name.lowercase())
                put("detail", r.detail)
                put("output", r.output)
                put("finished_at", r.finishedAtMs)
                put("audit_note", r.auditNote)
            },
        )
    }
}
