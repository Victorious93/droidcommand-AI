package ai.droidcommand.remotecontrol

/**
 * In-memory status tray. Nodes **publish** a snapshot; the controller
 * **consumes** it. Lost on process death. No live Messenger bind.
 */
data class NodeStatusSnapshot(
    val sourceNodeRef: String,
    val actionRef: String = "status",
    val payload: Map<String, String> = emptyMap(),
    val receivedAtMs: Long,
) {
    init {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
    }
}

interface RemoteStatusTray {
    fun consume(snapshot: NodeStatusSnapshot)
    fun publish(): List<NodeStatusSnapshot>
}

class InMemoryRemoteStatusTray(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
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

    fun consumeEnvelope(envelope: FleetRemoteEnvelope, source: SourceIdentity) {
        consume(
            NodeStatusSnapshot(
                sourceNodeRef = source.sourceNodeRef,
                actionRef = envelope.actionRef,
                payload = envelope.args,
                receivedAtMs = nowMs(),
            ),
        )
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
