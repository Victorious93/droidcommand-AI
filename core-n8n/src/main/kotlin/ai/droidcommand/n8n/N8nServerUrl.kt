package ai.droidcommand.n8n

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * A self-hosted n8n base URL the owner typed in.
 *
 * HTTPS is always allowed. Plain HTTP is allowed only for a LAN / loopback /
 * `.local` host, and always carries a warning. Cloud-metadata addresses and
 * credentials embedded in the URL are refused.
 */
data class N8nServerUrl(
    val raw: String,
    val base: String,
    val host: String,
    val https: Boolean,
    val warning: String?,
) {
    fun api(path: String): String = join(base, path)

    fun webhook(path: String): String = join(base, "webhook/${path.trim().trimStart('/')}")

    companion object {
        fun parse(input: String): N8nUrlParse = parseUri(input.trim())

        private fun parseUri(trimmed: String): N8nUrlParse {
            if (trimmed.isEmpty()) return N8nUrlParse.Rejected("Enter the address of your n8n server.")
            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return N8nUrlParse.Rejected("That does not look like a web address.")
            }
            val scheme = uri.scheme?.lowercase()
                ?: return N8nUrlParse.Rejected("Start the address with https:// or http://.")
            if (scheme != "https" && scheme != "http") {
                return N8nUrlParse.Rejected("Only https:// and http:// addresses are allowed.")
            }
            if (!uri.userInfo.isNullOrEmpty()) {
                return N8nUrlParse.Rejected("Do not put a username or password in the address. Save the API key in the key box instead.")
            }
            val host = uri.host?.trim()?.trim('[', ']')?.lowercase()
                ?: return N8nUrlParse.Rejected("The address is missing a computer name or number.")
            if (host.isEmpty()) return N8nUrlParse.Rejected("The address is missing a computer name or number.")
            if (isBlockedMetadata(host)) {
                return N8nUrlParse.Rejected("That address is blocked. It is used by cloud machines, not a home n8n server.")
            }
            val https = scheme == "https"
            val lan = isLanHost(host)
            if (!https && !lan) {
                return N8nUrlParse.Rejected(
                    "Plain http:// is only allowed for a home/LAN address (for example 192.168.x.x or a .local name). Use https:// on the public internet.",
                )
            }
            val warning = if (!https) {
                "This uses plain HTTP on your local network. Anyone on that network can see the traffic, including the API key. Prefer HTTPS when you can."
            } else {
                null
            }
            return N8nUrlParse.Ok(
                N8nServerUrl(
                    raw = trimmed,
                    base = normalizeBase(uri, scheme, host),
                    host = host,
                    https = https,
                    warning = warning,
                ),
            )
        }

        internal fun isLanHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val address = literalAddress(host) ?: return false
            if (isBlockedMetadata(host)) return false
            return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        }

        internal fun isBlockedMetadata(host: String): Boolean {
            if (host == "169.254.169.254" || host == "metadata.google.internal" || host == "metadata") return true
            return host.endsWith(".internal") && host.contains("metadata")
        }

        private fun literalAddress(host: String): InetAddress? {
            val ipv4 = host.matches(IPV4)
            val ipv6 = host.contains(':')
            if (!ipv4 && !ipv6) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }

        private fun normalizeBase(uri: URI, scheme: String, host: String): String {
            val port = uri.port
            val hostPart = if (host.contains(':')) "[$host]" else host
            val authority = if (port == -1) hostPart else "$hostPart:$port"
            val path = uri.path?.trimEnd('/') ?: ""
            return "$scheme://$authority$path"
        }

        private fun join(base: String, path: String): String {
            val suffix = path.trim().trimStart('/')
            return if (suffix.isEmpty()) base else "$base/$suffix"
        }

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}

sealed class N8nUrlParse {
    data class Ok(val url: N8nServerUrl) : N8nUrlParse()
    data class Rejected(val reason: String) : N8nUrlParse()
}
