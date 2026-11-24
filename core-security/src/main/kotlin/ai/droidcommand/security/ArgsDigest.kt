package ai.droidcommand.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * A fingerprint of a job's arguments, so a batch approval can be bound to *exactly what the owner reviewed*.
 *
 * The JSON is put in one canonical form before hashing: object keys sorted, no whitespace, strings escaped only where JSON
 * requires it (quote, backslash, control characters), numbers and literals exactly as they were written. Key order and
 * spacing therefore never change the digest; a changed value, an extra key, or a reordered array always does.
 *
 * Both sides of the comparison are parsed by this phone from the coordinator's own messages (the plan manifest, then the
 * assignment), so nothing here has to agree with how any other language canonicalises JSON.
 */
object ArgsDigest {
    /** Lower-case hex SHA-256 of [args] in canonical form. */
    fun of(args: JsonElement): String {
        val out = StringBuilder()
        write(args, out)
        return MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The canonical text itself, for tests and diagnostics. */
    fun canonical(args: JsonElement): String = StringBuilder().also { write(args, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) quote(e.content, out) else out.append(e.content)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(',')
                    quote(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
