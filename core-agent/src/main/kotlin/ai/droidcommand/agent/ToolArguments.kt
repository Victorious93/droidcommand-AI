package ai.droidcommand.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/**
 * One JSON-typed tool argument. Existing [Tool.execute] still takes
 * `Map<String, String>`; [ToolArgumentCodec.toStringMap] is the
 * compatibility adapter the owner's Kai prompt asked for rather than a
 * signature change across every tool.
 *
 * A JSON `null` is omitted by [ToolArgumentCodec.fromJsonMap], matching
 * `core-mcp.McpToolServer`'s already-shipped "explicit null means absent"
 * rule — it is not represented here.
 */
sealed class ToolArgumentValue {
    data class Text(val value: String) : ToolArgumentValue()

    /** [raw] is the JSON number's literal text (`42`, `3.5`) so integers stay integers. */
    data class Number(val raw: String) : ToolArgumentValue()

    data class Bool(val value: Boolean) : ToolArgumentValue()

    /** Compact JSON text of an array or object, never silently dropped. */
    data class NestedJson(val json: String) : ToolArgumentValue()
}

/**
 * A single tool invocation with a stable call id. Ids are for tracing and
 * for refusing a completed side-effect replay; they are **not** passed
 * into [Tool.execute]'s string map.
 */
data class ToolCallEnvelope(
    val callId: String,
    val toolName: String,
    val arguments: Map<String, ToolArgumentValue>,
) {
    init {
        require(callId.isNotBlank()) { "callId must not be blank" }
        require(toolName.isNotBlank()) { "toolName must not be blank" }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Converts MCP/provider JSON arguments to [ToolArgumentValue]s and back to
 * the string map every existing [Tool] already implements.
 *
 * Behaviour matches the 2026-10-07 `McpToolServer` adapter: primitives
 * become their raw text (`42` → `"42"`, `"hi"` → `"hi"`), arrays/objects
 * become compact JSON text, JSON `null` is dropped.
 */
object ToolArgumentCodec {
    fun fromJsonMap(arguments: Map<String, JsonElement>?): Map<String, ToolArgumentValue> {
        if (arguments.isNullOrEmpty()) return emptyMap()
        val out = linkedMapOf<String, ToolArgumentValue>()
        for ((key, value) in arguments) {
            if (value is JsonNull) continue
            out[key] = fromJsonElement(value)
        }
        return out
    }

    fun fromJsonElement(value: JsonElement): ToolArgumentValue = when (value) {
        is JsonNull -> error("JSON null must be omitted, not converted")
        is JsonPrimitive -> primitive(value)
        is JsonArray, is JsonObject -> ToolArgumentValue.NestedJson(value.toString())
    }

    fun toStringMap(arguments: Map<String, ToolArgumentValue>): Map<String, String> =
        arguments.mapValues { (_, value) -> toStringValue(value) }

    fun toStringValue(value: ToolArgumentValue): String = when (value) {
        is ToolArgumentValue.Text -> value.value
        is ToolArgumentValue.Number -> value.raw
        is ToolArgumentValue.Bool -> value.value.toString()
        is ToolArgumentValue.NestedJson -> value.json
    }

    private fun primitive(value: JsonPrimitive): ToolArgumentValue = when {
        value.isString -> ToolArgumentValue.Text(value.content)
        value.booleanOrNull != null && (value.content == "true" || value.content == "false") ->
            ToolArgumentValue.Bool(value.content == "true")
        else -> ToolArgumentValue.Number(value.content)
    }
}
