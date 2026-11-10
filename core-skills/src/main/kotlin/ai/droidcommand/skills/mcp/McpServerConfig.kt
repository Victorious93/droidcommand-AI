package ai.droidcommand.skills.mcp

import java.net.URI

/**
 * One MCP server the owner added in Settings. No secret lives here: a token for the server is kept in the app's
 * encrypted key storage under [McpSecrets.tokenId].
 *
 * [id] is a short slug used inside tool names (`mcp_<id>_<tool>`), so a server's tools can never take the
 * name of a built-in tool or of another server's tool.
 */
data class McpServerConfig(
    val id: String,
    val label: String,
    val url: String,
    val enabled: Boolean = true,
) {
    init {
        require(ID_PATTERN.matches(id)) { "MCP server id must be 1-12 lower-case letters or digits, was '$id'" }
    }

    companion object {
        val ID_PATTERN = Regex("[a-z0-9]{1,12}")

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
