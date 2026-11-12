package ai.droidcommand.skills.mcp

import java.net.URI
import java.util.UUID

/**
 * One MCP server the owner added in Settings. No secret lives here: a token for the server is kept in the app's
 * encrypted key storage under [McpSecrets.tokenId].
 *
 * [id] is a short slug used inside tool names (`mcp_<id>_<tool>`), so a server's tools can never take the
 * name of a built-in tool or of another server's tool. It comes from the label, so a server added later under
 * the same label can get the same [id] again.
 *
 * [instanceId] names this one saved entry and is never used again: a server the owner adds gets a fresh
 * [newInstanceId], even when it reuses a removed server's label and [id]. A helper bot's saved MCP tool is tied
 * to it (see [McpToolBinding]), so a replacement server is never mistaken for the one the owner picked. It stays
 * the same when the server is turned off and on. Anything that changes [url] must also give a new [instanceId].
 * The default, [legacyInstanceId], is what a server saved before instance ids existed reads as.
 */
data class McpServerConfig(
    val id: String,
    val label: String,
    val url: String,
    val enabled: Boolean = true,
    val instanceId: String = legacyInstanceId(id),
) {
    init {
        require(ID_PATTERN.matches(id)) { "MCP server id must be 1-12 lower-case letters or digits, was '$id'" }
        require(INSTANCE_PATTERN.matches(instanceId)) { "MCP server instance id is not valid" }
    }

    companion object {
        val ID_PATTERN = Regex("[a-z0-9]{1,12}")
        val INSTANCE_PATTERN = Regex("[A-Za-z0-9-]{1,64}")

        /** A fresh, random instance id for a server the owner is adding now. */
        fun newInstanceId(): String = UUID.randomUUID().toString()

        /** The instance id of a server that was saved before instance ids existed. Never given to a new server. */
        fun legacyInstanceId(id: String): String = "pre-$id"

        /** A fresh id for [label] that is not in [taken]: the label's letters/digits (max 8) plus a number if needed. */
        fun newId(label: String, taken: Set<String>): String {
            val base = label.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8).ifEmpty { "server" }
            if (base !in taken) return base
            var n = 2
            while ("$base$n" in taken) n++
            return "$base$n"
        }
    }
}

/** Where an MCP server's token is kept in the encrypted key storage. */
object McpSecrets {
    fun tokenId(serverId: String): String = "mcp.server.$serverId.token"
}

/** Checks an MCP server address the owner typed. */
object McpServerUrl {
    sealed class Check {
        data class Ok(val url: String, val warning: String? = null) : Check()
        data class Rejected(val reason: String) : Check()
    }

    fun check(raw: String): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Rejected("Type the server address, for example https://my-pc.local:8080/mcp")
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Check.Rejected("That address does not look right.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Check.Rejected("The address must start with https:// or http://")
        if (uri.host.isNullOrBlank()) return Check.Rejected("The address needs a computer name or IP address.")
        if (uri.userInfo != null) return Check.Rejected("Do not put a password in the address. Use the token box instead.")
        val warning = if (scheme == "http") {
            "This address is not encrypted (http). Only use it on your own home network."
        } else {
            null
        }
        return Check.Ok(text, warning)
    }
}
