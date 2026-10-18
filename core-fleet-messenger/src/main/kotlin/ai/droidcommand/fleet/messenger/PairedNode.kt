package ai.droidcommand.fleet.messenger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One EXTERNAL_NODE the owner has paired: which installed package it is, the identity the node must
 * report in every response, and the SHA-256 of the signing certificate that package must carry.
 *
 * Nothing about any node is built into this class or this module. The certificate comes from the
 * pairing (what the owner confirmed out of band), never from code, so a re-signed or impostor
 * package with the same name is refused. Trust is package + certificate allowlist; there is no
 * shared signing key, no `DependencyGuard` and no AIDL involved.
 */
@Serializable
data class PairedNode(
    @SerialName("package") val packageName: String,
    @SerialName("node_id") val nodeId: String,
    /** Lower-case hex, 64 chars, no separators. Use [of] to accept `AA:BB:..` / upper-case input. */
    @SerialName("signing_cert_sha256") val expectedCertSha256: String,
    /** Override only if the node's service answers an action other than `<package>.action.BRIDGE`. */
    @SerialName("action") val customAction: String? = null,
) {
    init {
        require(PACKAGE_RE.matches(packageName)) { "package must be an Android application id" }
        require(NODE_ID_RE.matches(nodeId)) { "node_id contains unsupported characters" }
        require(CERT_RE.matches(expectedCertSha256)) { "signing_cert_sha256 must be 64 lower-case hex characters" }
        customAction?.let { require(ACTION_RE.matches(it)) { "action contains unsupported characters" } }
    }

    /** The intent action the bridge service is bound by. */
    val action: String get() = customAction ?: MessengerWire.defaultAction(packageName)

    companion object {
        private val PACKAGE_RE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
        private val NODE_ID_RE = Regex("""^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$""")
        private val CERT_RE = Regex("""^[0-9a-f]{64}$""")
        private val ACTION_RE = Regex("""^[A-Za-z0-9_.]{1,255}$""")

        /** Builds a node from a fingerprint as `apksigner` / keytool print it (case and `:` separators tolerated). */
        fun of(packageName: String, nodeId: String, certSha256: String, customAction: String? = null): PairedNode =
            PairedNode(packageName, nodeId, normalizeCertSha256(certSha256), customAction)

        fun normalizeCertSha256(raw: String): String = raw.trim().replace(":", "").lowercase()
    }
}

/** Source of pairings. Empty by default: with nothing paired, no node is ever contacted. */
interface PairedNodeStore {
    fun all(): List<PairedNode>

    fun find(nodeId: String): PairedNode? = all().firstOrNull { it.nodeId == nodeId }
}

class InMemoryPairedNodeStore(nodes: List<PairedNode> = emptyList()) : PairedNodeStore {
    private val nodes = nodes.toMutableList()

    init {
        PairedNodeCodec.requireDistinct(nodes)
    }

    @Synchronized
    override fun all(): List<PairedNode> = nodes.toList()

    @Synchronized
    fun pair(node: PairedNode) {
        PairedNodeCodec.requireDistinct(nodes + node)
        nodes += node
    }

    @Synchronized
    fun unpair(nodeId: String): Boolean = nodes.removeAll { it.nodeId == nodeId }
}

@Serializable
private data class PairedNodeFile(val nodes: List<PairedNode> = emptyList())

/** `{"nodes":[{"package":"...","node_id":"...","signing_cert_sha256":"..."}]}`, the persisted pairing document. */
object PairedNodeCodec {
    private val json = Json { ignoreUnknownKeys = false }

    /** Throws on malformed, invalid or duplicate entries — a half-trusted pairing file is never partially applied. */
    fun decode(text: String): List<PairedNode> {
        val nodes = json.decodeFromString<PairedNodeFile>(text).nodes
        requireDistinct(nodes)
        return nodes
    }

    fun encode(nodes: List<PairedNode>): String {
        requireDistinct(nodes)
        return json.encodeToString(PairedNodeFile.serializer(), PairedNodeFile(nodes))
    }

    internal fun requireDistinct(nodes: List<PairedNode>) {
        require(nodes.map { it.nodeId }.toSet().size == nodes.size) { "duplicate node_id in pairings" }
        require(nodes.map { it.packageName }.toSet().size == nodes.size) { "duplicate package in pairings" }
    }
}
