package ai.droidcommand.remotecontrol

/**
 * Shared fleet status-tray domains (mesh / VPN / browser / SMS / node health).
 *
 * Exact shared schema/transport with VictorLaunch is
 * **[CONTRACT TBD pending Fleet Orchestrator]**. This is a generic model
 * the controller can consume and publish.
 */
enum class StatusTrayDomain {
    MESH,
    VPN,
    BROWSER,
    SMS,
    NODE_HEALTH,
    OTHER,
    ;

    companion object {
        fun fromSource(source: SourceIdentity, envelope: FleetRemoteEnvelope): StatusTrayDomain {
            val haystack = listOfNotNull(
                source.packageName,
                source.sourceNodeRef,
                envelope.actionRef,
                envelope.args["domain"],
            ).joinToString(" ").lowercase()
            return when {
                haystack.contains("mesh") -> MESH
                haystack.contains("vpn") -> VPN
                haystack.contains("browser") -> BROWSER
                haystack.contains("sms") || haystack.contains("victorsms") -> SMS
                haystack.contains("health") || haystack.contains("status") -> NODE_HEALTH
                else -> OTHER
            }
        }

        fun fromWire(value: String): StatusTrayDomain =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/**
 * In-memory status tray. Nodes **publish** a snapshot; the controller
 * **consumes** it. Lost on process death. No live Messenger bind.
 */
data class NodeStatusSnapshot(
    val sourceNodeRef: String,
    val domain: StatusTrayDomain = StatusTrayDomain.NODE_HEALTH,
    val capability: String = "status",
    val healthy: Boolean? = null,
    val actionRef: String = "status",
    val payload: Map<String, String> = emptyMap(),
    val receivedAtMs: Long,
) {
    init {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
        require(capability.isNotBlank()) { "capability is required" }
    }
}

interface RemoteStatusTray {
    fun consume(snapshot: NodeStatusSnapshot)
    fun publish(): List<NodeStatusSnapshot>
}

class InMemoryRemoteStatusTray(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : RemoteStatusTray {
    private val lock = Any()
    private val latest = LinkedHashMap<String, NodeStatusSnapshot>()

    override fun consume(snapshot: NodeStatusSnapshot) {
        synchronized(lock) {
            latest.remove(snapshot.sourceNodeRef)
            latest[snapshot.sourceNodeRef] = snapshot
            while (latest.size > maxEntries) {
                val oldest = latest.keys.first()
                latest.remove(oldest)
            }
        }
    }

    override fun publish(): List<NodeStatusSnapshot> = synchronized(lock) {
        latest.values.toList()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
