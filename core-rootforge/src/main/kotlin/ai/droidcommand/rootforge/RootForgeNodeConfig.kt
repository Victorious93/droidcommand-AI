package ai.droidcommand.rootforge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One paired RootForge machine. Everything needed to reach it AND to refuse an impostor:
 * [nodeId] is the identity the node reports in every response, and [hostKey] is the SSH host
 * public key line confirmed out of band (`rootforge bridge host-key` on the node). A changed host
 * key therefore fails the connection instead of being re-learned.
 */
@Serializable
data class RootForgeNodeConfig(
    @SerialName("node_id") val nodeId: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    @SerialName("identity_file") val identityFile: String,
    @SerialName("host_key") val hostKey: String,
) {
    init {
        require(NODE_ID_RE.matches(nodeId)) { "node_id must look like rf-<32 hex>" }
        require(HOST_RE.matches(host) && !host.startsWith("-")) { "host contains unsupported characters" }
        require(port in 1..65535) { "port out of range" }
        require(USER_RE.matches(user)) { "user contains unsupported characters" }
        require(identityFile.isNotBlank() && !identityFile.startsWith("-")) { "identity_file must be a path" }
        require(HOST_KEY_RE.matches(hostKey)) { "host_key must be a single OpenSSH public key line (type + base64)" }
    }

    /** The key algorithm, used to stop ssh from negotiating any other host key type. */
    val hostKeyType: String get() = hostKey.substringBefore(' ')

    companion object {
        private val NODE_ID_RE = Regex("^rf-[0-9a-f]{32}$")
        private val HOST_RE = Regex("^[A-Za-z0-9._-]+$|^[0-9A-Fa-f:]+$")
        private val USER_RE = Regex("^[a-z_][a-z0-9_-]{0,31}$")
        private val HOST_KEY_RE = Regex("^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) [A-Za-z0-9+/=]+$")
    }
}

@Serializable
private data class NodeFile(val nodes: List<RootForgeNodeConfig>)

/** Parses the node list file (`{"nodes":[{...}]}`). Throws on malformed or duplicate entries. */
fun parseNodeConfigs(text: String): List<RootForgeNodeConfig> {
    val nodes = Json.decodeFromString<NodeFile>(text).nodes
    val ids = nodes.map { it.nodeId }
    require(ids.size == ids.toSet().size) { "duplicate node_id in node configuration" }
    return nodes
}
