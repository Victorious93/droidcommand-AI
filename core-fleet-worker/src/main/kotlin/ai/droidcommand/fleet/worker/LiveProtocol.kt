package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.Base64

/**
 * Protocol 1.1 (terminal + messaging) — the Kotlin twin of `victorfleet/protocol.py`'s 1.1 section. Same golden fixtures on both
 * sides (`fleet-fixtures/v1`), including the cross-implementation hashes below.
 */
object LiveProtocol {
    val OP_STATUSES = setOf("ready", "permission_needed", "owner_interaction_required", "version_outdated", "not_installed", "untrusted", "revoked", "unavailable")
    const val OP_TERMINAL = "terminal.session"
    const val OP_ONESHOT = "shell.oneshot"
    const val OP_ROOT = "terminal.root"
    const val OP_DRAFT_SMS = "message.draft.sms"
    const val OP_DRAFT_EMAIL = "message.draft.email"
    val PRIVILEGES = setOf("user", "root")
    val MODES = setOf("interactive", "oneshot")
    val WORKER_TERMINAL_STATES = setOf("awaiting_approval", "open", "denied", "closed", "failed", "revoked")
    val DRAFT_WORKER_STATES = setOf("received", "declined", "submitted", "failed")
    val CHUNK_KINDS = setOf("data", "resize", "signal", "eof", "exit", "close")
    val SIGNALS = setOf("INT", "TERM", "HUP", "QUIT", "TSTP", "CONT")
    const val MAX_CHUNK_BYTES = 16 * 1024
    const val MAX_COMMAND_CHARS = 4096

    private fun bad(m: String, code: String = "bad_request"): Nothing = throw FleetProtocolException(code, m)
    private fun JsonObject.s(k: String, max: Int = 4096, optional: Boolean = false): String? {
        val v = this[k]
        if (v == null) {
            if (optional) return null
            bad("field '$k' missing")
        }
        val p = v as? JsonPrimitive ?: bad("field '$k' not a string")
        if (!p.isString) bad("field '$k' not a string")
        if (p.content.length > max) bad("'$k' too long", "too_large")
        return p.content
    }
    private fun JsonObject.l(k: String): Long = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: bad("field '$k' missing or not an integer")

    /** Byte-identical to `terminal_binding_hash` in Python: sha256("victorfleet-terminal-v1\n" + compact ascii JSON list). */
    fun terminalBindingHash(session: String, device: String, desktop: String, privilege: String, mode: String, command: String?, nonce: String): String {
        val list = JsonArray(listOf(session, device, desktop, privilege, mode).map(::JsonPrimitive) + (command?.let(::JsonPrimitive) ?: JsonNull) + JsonPrimitive(nonce))
        return sha("victorfleet-terminal-v1\n" + pyJson(list, false))
    }

    /** Byte-identical to `draft_content_hash` in Python (sorted keys, compact, ensure_ascii). */
    fun draftContentHash(channel: String, fields: JsonObject): String = sha("victorfleet-draft-v1\n$channel\n" + pyJson(fields, true))

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Python `json.dumps(x, separators=(",", ":"), ensure_ascii=True, sort_keys=sort)`. Integers/strings/bools/null/arrays/objects only. */
    fun pyJson(e: JsonElement, sort: Boolean): String = when (e) {
        is JsonNull -> "null"
        is JsonArray -> e.joinToString(",", "[", "]") { pyJson(it, sort) }
        is JsonObject -> (if (sort) e.entries.sortedBy { it.key } else e.entries.toList()).joinToString(",", "{", "}") { pyStr(it.key) + ":" + pyJson(it.value, sort) }
        is JsonPrimitive -> if (e.isString) pyStr(e.content) else e.content
    }

    internal fun pyStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000c' -> sb.append("\\f")
            ch.code < 0x20 || ch.code > 0x7e -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    fun validateTerminalOpen(p: JsonObject) {
        p.s("session_id", 96)
        p.s("desktop", 128)
        if (p.s("mode", 16) !in MODES) bad("mode must be interactive|oneshot")
        if (p.s("privilege", 8) !in PRIVILEGES) bad("privilege must be user|root")
        p.l("cols")
        p.l("rows")
        p.s("nonce", 64)
        p.l("expires_ms")
        p.s("binding", 64)
        val cmd = p["command"]
        if (p.s("mode") == "oneshot") {
            val c = (cmd as? JsonPrimitive)?.contentOrNull
            if (c.isNullOrBlank() || c.length > MAX_COMMAND_CHARS) bad("oneshot needs a command")
        } else if (cmd != null && cmd !is JsonNull) {
            bad("interactive sessions carry no command")
        }
    }

    fun validateTerminalStatus(p: JsonObject) {
        p.s("session_id", 96)
        val st = p.s("state", 24)
        if (st !in WORKER_TERMINAL_STATES) bad("terminal_status.state invalid")
        p.s("binding", 64, true)
        p.s("reason", 256, true)
        if (st == "open" && p["binding"] == null) bad("an 'open' status must echo the approval binding")
    }

    fun validateDraftStatus(p: JsonObject) {
        p.s("draft_id", 96)
        if (p.s("state", 16) !in DRAFT_WORKER_STATES) bad("draft_status.state invalid")
        p.s("content_hash", 64, true)
        p.s("detail", 256, true)
    }

    fun validateDraftDeliver(p: JsonObject) {
        p.s("draft_id", 96)
        p.s("origin", 128)
        p.l("expires_ms")
        val ch = p.s("channel", 8)
        if (ch != "sms" && ch != "email") bad("channel must be sms|email")
        val d = p["draft"] as? JsonObject ?: bad("draft missing")
        val allowed = if (ch == "sms") setOf("to", "body", "sim_hint") else setOf("to", "cc", "bcc", "subject", "body", "account_hint", "attachments")
        (d.keys - allowed).takeIf { it.isNotEmpty() }?.let { bad("unknown draft fields: $it") }
        if ((d["to"] as? JsonArray).isNullOrEmpty()) bad("a draft needs recipients")
        d.s("body", 100_000)
        if (draftContentHash(ch!!, d) != p.s("content_hash", 64)) bad("content_hash does not match the draft")
    }

    fun validateChunk(c: JsonObject) {
        if (c.l("seq") < 1) bad("seq starts at 1")
        val kind = c.s("kind", 16)
        if (kind !in CHUNK_KINDS) bad("unknown chunk kind")
        if (kind == "data") {
            val b = runCatching { Base64.getDecoder().decode(c.s("data", 40_000)) }.getOrElse { bad("data is not valid base64") }
            if (b.size > MAX_CHUNK_BYTES) bad("chunk over 16 KiB", "too_large")
        }
    }

    fun boolOf(e: JsonElement?) = (e as? JsonPrimitive)?.booleanOrNull
}
