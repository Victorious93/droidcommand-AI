package ai.droidcommand.flash

import ai.droidcommand.rootforge.SshTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One paired Victorious Flash machine. [nodeId] is what `victorious-flash-bridge node-id` prints on
 * the node and what it reports in every response; [hostKey] is that machine's SSH host public key
 * line, confirmed out of band. Same validation rules as `RootForgeNodeConfig`.
 */
@Serializable
data class FlashNodeConfig(
    @SerialName("node_id") val nodeId: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    @SerialName("identity_file") val identityFile: String,
    @SerialName("host_key") val hostKey: String,
) {
    init {
        require(NODE_ID_RE.matches(nodeId)) { "node_id must look like vf-<32 hex>" }
        require(HOST_RE.matches(host) && !host.startsWith("-")) { "host contains unsupported characters" }
        require(port in 1..65535) { "port out of range" }
        require(USER_RE.matches(user)) { "user contains unsupported characters" }
        require(identityFile.isNotBlank() && !identityFile.startsWith("-")) { "identity_file must be a path" }
        require(HOST_KEY_RE.matches(hostKey)) { "host_key must be a single OpenSSH public key line (type + base64)" }
    }

    val sshTarget: SshTarget get() = SshTarget(host, port, user, identityFile, hostKey)

    companion object {
        private val NODE_ID_RE = Regex("^vf-[0-9a-f]{32}$")
        private val HOST_RE = Regex("^[A-Za-z0-9._-]+$|^[0-9A-Fa-f:]+$")
        private val USER_RE = Regex("^[a-z_][a-z0-9_-]{0,31}$")
        private val HOST_KEY_RE = Regex("^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) [A-Za-z0-9+/=]+$")
    }
}

@Serializable
private data class FlashNodeFile(val nodes: List<FlashNodeConfig>)

/** Parses `{"nodes":[{...}]}`. Throws on malformed or duplicate entries. */
fun parseFlashNodeConfigs(text: String): List<FlashNodeConfig> {
    val nodes = Json.decodeFromString<FlashNodeFile>(text).nodes
    require(nodes.map { it.nodeId }.toSet().size == nodes.size) { "duplicate node_id in node configuration" }
    return nodes
}

/** Exact-id lookup. No default node, no prefix match, no fail-over. */
class FlashNodeRegistry(
    nodes: List<FlashNodeConfig>,
    private val clientFactory: (FlashNodeConfig) -> FlashClient = { FlashClient(it, SshFlashTransport(it)) },
) {
    private val byId = nodes.associateBy { it.nodeId }

    init {
        require(byId.size == nodes.size) { "duplicate node_id in registry" }
    }

    val nodeIds: Set<String> get() = byId.keys

    fun clientFor(nodeId: String): FlashClient? = byId[nodeId]?.let(clientFactory)
}
