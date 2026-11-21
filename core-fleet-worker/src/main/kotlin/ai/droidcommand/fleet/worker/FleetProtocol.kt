package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Structured protocol error; [code] is always one of [FleetProtocol.ERROR_CODES] so both sides agree on meaning. */
class FleetProtocolException(val code: String, message: String) : Exception(message) {
    init {
        require(code in FleetProtocol.ERROR_CODES) { "unknown error code $code" }
    }
}

/**
 * The coordinator/worker contract, protocol_major 1. The rules here are the Kotlin twin of VictorLinux
 * `fleet/victorfleet/protocol.py`; both are exercised by the same golden fixtures (`fleet-fixtures/v1`, guarded by
 * MANIFEST.sha256) so a drift on either side fails a test instead of a phone in the field.
 * Compatibility: unknown optional fields are ignored; an unknown message type or a different major is rejected.
 */
object FleetProtocol {
    const val MAJOR = 1
    const val MINOR = 1
    const val MAX_MESSAGE_BYTES = 256 * 1024
    const val MAX_ARGS_BYTES = 64 * 1024
    const val MAX_BATCH = 64
    val ID_RE = Regex("^[A-Za-z0-9._:-]{1,96}$")
    val OP_RE = Regex("^[a-z][a-z0-9_.]{0,63}$")
    val SHA256_RE = Regex("^[0-9a-f]{64}$")
    val ARTIFACT_NAME_RE = Regex("^[A-Za-z0-9._-]{1,128}$")
    val WORKER_TYPES = setOf(
        "enroll_request", "hello", "heartbeat", "ack", "progress", "complete", "fail", "cancel_ack",
        "approval_needed", "artifact_offer", "node_message", "terminal_status", "draft_status",
    )
    val COORD_TYPES = setOf("enroll_response", "welcome", "assignment", "cancel", "revoke", "artifact_grant", "node_message", "reject", "terminal_open", "terminal_close", "draft_deliver")
    val ERROR_CODES = setOf(
        "bad_request", "unauthorized", "revoked", "unsupported_protocol", "unknown_type", "too_large", "stale_attempt",
        "unknown_job", "quota_exceeded", "capability_missing", "permission_denied", "approval_required", "device_busy", "deadline_exceeded",
        "cancelled", "executor_unavailable", "internal", "declined", "revoked_session", "version_outdated", "broker_untrusted",
    )
    val THERMAL = setOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown", "unknown")
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun envelope(type: String, sender: String, target: String, payload: JsonObject, msgId: String = newId(), nowMs: Long = System.currentTimeMillis()): JsonObject =
        buildJsonObject {
            put(
                "protocol",
                buildJsonObject {
                    put("major", MAJOR)
                    put("minor", MINOR)
                },
            )
            put("msg_id", msgId)
            put("type", type)
            put("sender", sender)
            put("target", target)
            put("sent_at", nowMs)
            put("payload", payload)
        }

    private fun bad(msg: String, code: String = "bad_request"): Nothing = throw FleetProtocolException(code, msg)

    private fun JsonObject.str(key: String, max: Int = 4096, optional: Boolean = false): String? {
        val v = this[key]
        if (v == null || v is JsonNull) {
            if (optional) return null else bad("field '$key' missing")
        }
        val p = v as? JsonPrimitive
        if (p == null || !p.isString) bad("field '$key' is not a string")
        if (p.content.length > max) bad("'$key' too long", "too_large")
        return p.content
    }

    private fun JsonObject.int(key: String): Long {
        val p = this[key] as? JsonPrimitive ?: bad("field '$key' missing")
        if (p.isString) bad("field '$key' is not an integer")
        return p.longOrNull ?: bad("field '$key' is not an integer")
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: bad("field '$key' missing or not an object")

    private fun JsonObject.id(key: String, optional: Boolean = false): String? {
        val v = str(key, 96, optional)
        if (v != null && !ID_RE.matches(v)) bad("'$key' is not a valid identifier")
        return v
    }

    fun validateEnvelope(e: JsonElement, expectSender: String? = null): JsonObject {
        val o = e as? JsonObject ?: bad("envelope must be an object")
        val proto = o.obj("protocol")
        if (proto.int("major") != MAJOR.toLong()) bad("peer speaks a different protocol major", "unsupported_protocol")
        proto.int("minor")
        o.id("msg_id")
        o.id("sender")
        o.id("target")
        val t = o.str("type", 64)!!
        o.int("sent_at")
        o.obj("payload")
        if (t !in WORKER_TYPES && t !in COORD_TYPES) bad("unknown message type '$t'", "unknown_type")
        if (expectSender != null && o.str("sender") != expectSender) bad("sender does not match the authenticated identity", "unauthorized")
        return o
    }

    fun validateArtifactRef(a: JsonObject) {
        val name = a.str("name", 128)!!
        if (!ARTIFACT_NAME_RE.matches(name) || name == "." || name == "..") bad("artifact name must be a plain filename")
        a.int("size")
        if (!SHA256_RE.matches(a.str("sha256", 64)!!)) bad("artifact sha256 must be 64 lowercase hex chars")
    }

    fun validateDeviceState(d: JsonObject) {
        for (k in listOf("battery_percent", "free_storage_bytes")) {
            if (d.containsKey(k)) {
                if (d.int(k) < 0) bad("$k invalid")
            }
        }
        if (d.containsKey("battery_percent") && d.int("battery_percent") > 100) bad("battery_percent > 100")
        if (d.containsKey("charging") && (d["charging"] as? JsonPrimitive)?.booleanOrNull == null) bad("charging must be boolean")
        if (d.containsKey("thermal") && d.str("thermal") !in THERMAL) bad("thermal status invalid")
    }

    fun validateCapabilities(c: JsonObject) {
        val ops = c.obj("operations")
        if (ops.size > 64) bad("too many operations", "too_large")
        for ((name, v) in ops) {
            if (!OP_RE.matches(name)) bad("operation name '$name' invalid")
            val o = v as? JsonObject ?: bad("operation '$name' must be an object")
            if ((o["available"] as? JsonPrimitive)?.booleanOrNull == null) bad("operation '$name' needs boolean 'available'")
            if (o.containsKey("granted") && (o["granted"] as? JsonPrimitive)?.booleanOrNull == null) bad("operation '$name' granted must be boolean")
            if (o.containsKey("status") && o.str("status") !in LiveProtocol.OP_STATUSES) bad("operation '$name' status invalid")
        }
        (c["root"] as? JsonObject)?.let { r -> if (r.isNotEmpty() && (r["available"] as? JsonPrimitive)?.booleanOrNull == null) bad("root.available must be boolean") }
        validateDeviceState(c.obj("device_state"))
    }

    /** Validation of worker->coordinator payloads (what this module produces; also what the coordinator enforces). */
    fun validateWorkerPayload(type: String, p: JsonObject) {
        when (type) {
            "terminal_status" -> LiveProtocol.validateTerminalStatus(p)
            "draft_status" -> LiveProtocol.validateDraftStatus(p)
            "enroll_request" -> {
                p.str("device_name", 80)
                p.str("public_key_pem", 2048)
                p.str("pairing_proof", 256)
                p.str("signature_b64", 512)
                p.str("nonce", 128)
            }
            "hello" -> {
                validateCapabilities(p.obj("capabilities"))
                p.str("app_version", 64)
                (p["running"] as? JsonArray)?.forEach { r ->
                    val ro = r as? JsonObject ?: bad("running entries must be objects")
                    ro.id("job_id")
                    ro.int("attempt")
                }
            }
            "heartbeat" -> validateDeviceState(p.obj("device_state"))
            "ack", "cancel_ack", "approval_needed", "progress", "complete", "fail", "artifact_offer" -> {
                p.id("job_id")
                p.int("attempt")
                when (type) {
                    "progress" -> {
                        p["percent"]?.let { v ->
                            val d = (v as? JsonPrimitive)?.doubleOrNull
                            if (d == null || d < 0 || d > 100) bad("percent out of range")
                        }
                        p.str("note", 512, optional = true)
                    }
                    "complete" -> {
                        p.obj("result")
                        (p["artifacts"] as? JsonArray)?.forEach { validateArtifactRef(it as? JsonObject ?: bad("artifact must be an object")) }
                    }
                    "fail" -> {
                        val err = p.obj("error")
                        if (err.str("code") !in ERROR_CODES) bad("error.code is not a known code")
                        err.str("message", 512)
                        p["side_effects"]?.let { if ((it as? JsonPrimitive)?.contentOrNull !in setOf("none", "possible", "certain")) bad("side_effects must be none|possible|certain") }
                    }
                    "cancel_ack" -> if (p.str("state") !in setOf("stopped", "completed_before_cancel", "not_running")) bad("cancel_ack.state invalid")
                    "approval_needed" -> p.str("reason", 256)
                    "artifact_offer" -> validateArtifactRef(p.obj("artifact"))
                }
            }
            "node_message" -> {
                p.id("to_device")
                p.str("topic", 64)
                p.obj("body")
            }
            else -> bad("unknown worker message type '$type'", "unknown_type")
        }
    }

    /** Defensive validation of an inbound assignment. The worker never trusts a message just because it arrived. */
    fun validateAssignment(p: JsonObject) {
        p.id("job_id")
        if (p.int("attempt") < 1) bad("attempt must be >= 1")
        if (p.int("fence") != p.int("attempt")) bad("fence must equal attempt in protocol 1")
        val op = p.str("op", 64)!!
        if (!OP_RE.matches(op)) bad("op invalid")
        val args = p.obj("args")
        if (args.toString().length > MAX_ARGS_BYTES) bad("args too large", "too_large")
        (p["inputs"] as? JsonArray)?.forEach { i ->
            val io = i as? JsonObject ?: bad("input must be an object")
            io.id("artifact_id")
            validateArtifactRef(io)
        }
        p.int("lease_expires_ms")
    }
}
