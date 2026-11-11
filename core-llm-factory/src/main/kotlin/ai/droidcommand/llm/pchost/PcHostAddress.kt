package ai.droidcommand.llm.pchost

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * The two kinds of model server on the owner's own computer that Chat can use. [id] is the saved name,
 * [secretId] the `SecretsVault` id of the optional API key (most home servers need none).
 */
enum class PcHostKind(val id: String, val label: String, val secretId: String, val defaultPort: Int?, val example: String) {
    /** Ollama. Default port 11434 (Ollama's documented default); models are listed through its own `/api/tags`. */
    OLLAMA("ollama", "Ollama", "llm.pchost.ollama.api_key", 11434, "192.168.1.20:11434"),

    /** Any server with the OpenAI `/v1/chat/completions` shape: llama.cpp's `llama-server`, LM Studio, vLLM and others. */
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-compatible server", "llm.pchost.openai_compatible.api_key", null, "http://192.168.1.20:8080"),
    ;

    companion object {
        fun byId(id: String?): PcHostKind? = entries.firstOrNull { it.id == id }
    }
}

sealed class PcHostAddressParse {
    data class Ok(val address: PcHostAddress) : PcHostAddressParse()

    data class Rejected(val reason: String) : PcHostAddressParse()
}

/**
 * The address of a model server the owner typed, normalised. [base] has no trailing slash and no `/v1` (the chat
 * adapter adds `v1/chat/completions` itself), so `http://pc:1234/v1` and `http://pc:1234` are the same server.
 *
 * `https://` is always accepted. Plain `http://` is what a home server speaks, so it is accepted for an address that
 * stays on the owner's own network: loopback, the private IPv4 ranges, link-local, the 100.64.0.0/10 range VPNs such
 * as Tailscale hand out, and IPv6 unique-local addresses. A name instead of a number is accepted here and checked
 * when a request is about to be made ([cleartextProblem]), because only resolving it tells where it points.
 */
data class PcHostAddress(val base: String, val host: String, val https: Boolean) {
    /** Shown next to the address while it uses plain HTTP. */
    val warning: String?
        get() = if (https) null else CLEARTEXT_WARNING

    /**
     * Null when a request may be sent. For a plain-HTTP name, [resolve] must return only private addresses; a name
     * that resolves to the public internet is refused with the returned text. A name that does not resolve at all is
     * left to the request itself, which then reports the server as unreachable.
     */
    fun cleartextProblem(resolve: (String) -> List<InetAddress> = ::systemResolve): String? {
        if (https || literal(host) != null) return null
        val addresses = try {
            resolve(host)
        } catch (_: Exception) {
            return null
        }
        return if (addresses.all(::isPrivate)) null else PUBLIC_HTTP_REFUSED
    }

    companion object {
        const val CLEARTEXT_WARNING =
            "Plain HTTP: anyone on the same network can read the chat and the API key, if you saved one. " +
                "Use it only on your own Wi-Fi or VPN."
        const val PUBLIC_HTTP_REFUSED =
            "This name points to a public internet address, and plain http:// is only allowed for an address on your own " +
                "network or VPN. Use https://, or the PC's local or VPN address."

        fun parse(kind: PcHostKind, input: String): PcHostAddressParse {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return PcHostAddressParse.Rejected("Enter the address of your PC, for example ${kind.example}.")
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val uri = try {
                URI(withScheme)
            } catch (_: URISyntaxException) {
                return PcHostAddressParse.Rejected("That does not look like an address. Example: ${kind.example}.")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return PcHostAddressParse.Rejected("Only http:// and https:// addresses work here.")
            if (!uri.rawUserInfo.isNullOrEmpty()) {
                return PcHostAddressParse.Rejected("Do not put a user name or password in the address. Save the server's key in the key box instead.")
            }
            if (uri.rawQuery != null || uri.rawFragment != null) return PcHostAddressParse.Rejected("Leave out everything after ? or # in the address.")
            val host = uri.host?.trim('[', ']')?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return PcHostAddressParse.Rejected("The address is missing the PC's name or number. Example: ${kind.example}.")
            if (isMetadata(host)) return PcHostAddressParse.Rejected("That address belongs to cloud machines, not to a PC on your network.")
            val https = scheme == "https"
            val address = literal(host)
            if (!https && address != null && !isPrivate(address)) {
                return PcHostAddressParse.Rejected(
                    "Plain http:// is only allowed for an address on your own network or VPN (for example 192.168.x.x, 10.x.x.x or 100.x.x.x). " +
                        "Use https:// for a public address.",
                )
            }
            val port = when {
                uri.port != -1 -> uri.port
                !https -> kind.defaultPort ?: -1
                else -> -1
            }
            val hostPart = if (host.contains(':')) "[$host]" else host
            val authority = if (port == -1) hostPart else "$hostPart:$port"
            var path = uri.rawPath.orEmpty().trimEnd('/')
            // People paste the base URL other clients ask for; the adapters add these parts themselves.
            if (path.endsWith("/v1")) path = path.removeSuffix("/v1")
            if (kind == PcHostKind.OLLAMA && path.endsWith("/api")) path = path.removeSuffix("/api")
            return PcHostAddressParse.Ok(PcHostAddress("$scheme://$authority$path", host, https))
        }

        /** The address when [host] is written as a number; null for a name. Never does a DNS lookup. */
        internal fun literal(host: String): InetAddress? {
            if (!IPV4.matches(host) && !host.contains(':')) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }

        internal fun isPrivate(address: InetAddress): Boolean {
            val bytes = address.address
            if (isMetadata(address.hostAddress.orEmpty())) return false
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
            val first = bytes[0].toInt() and 0xff
            // 100.64.0.0/10: carrier-grade NAT space, which Tailscale and similar VPNs use for device addresses.
            if (bytes.size == 4) return first == 100 && (bytes[1].toInt() and 0xc0) == 0x40
            // fc00::/7: IPv6 unique-local addresses (home networks, WireGuard, ZeroTier).
            return (first and 0xfe) == 0xfc
        }

        private fun isMetadata(host: String) =
            host == "169.254.169.254" || host == "metadata" || host == "metadata.google.internal" || host == "fd00:ec2::254"

        private fun systemResolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
