package ai.droidcommand.app.mcp

import ai.droidcommand.skills.mcp.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The owner's MCP server list, as JSON text. No tokens here: those live in the encrypted key storage
 * (see [ai.droidcommand.skills.mcp.McpSecrets]). A broken saved list reads as empty rather than crashing.
 */
object McpServerCodec {
    fun encode(servers: List<McpServerConfig>): String = JsonArray(
        servers.map {
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(it.id),
                    "label" to JsonPrimitive(it.label),
                    "url" to JsonPrimitive(it.url),
                    "enabled" to JsonPrimitive(it.enabled),
                ),
            )
        },
    ).toString()

    fun decode(text: String?): List<McpServerConfig> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!McpServerConfig.ID_PATTERN.matches(id)) return@mapNotNull null
            McpServerConfig(
                id = id,
                label = (o["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
                url = url,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }.distinctBy { it.id }
    }
}
