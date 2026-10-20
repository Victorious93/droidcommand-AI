package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass

/**
 * Shared fleet status-tray domains (mesh / VPN / browser / SMS / node health).
 *
 * **[CONTRACT] FROZEN** — `docs/specs/STATUS_TRAY.md`. Wire names are the
 * lowercase [wire] values (`mesh`, `vpn`, `browser`, `sms`, `node_health`,
 * `other`). Unknown wire names decode as [OTHER]. Enum names match the
 * #233 skeleton and must not be renamed.
 */
enum class StatusTrayDomain(val wire: String) {
    MESH("mesh"),
    VPN("vpn"),
    BROWSER("browser"),
    SMS("sms"),
    NODE_HEALTH("node_health"),
    OTHER("other"),
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
            entries.find { it.wire.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: OTHER
    }
}

/**
 * Frozen domain state. Wire: `ok` / `degraded` / `down` / `unknown` / `off`.
 */
enum class StatusTrayState(val wire: String) {
    OK("ok"),
    DEGRADED("degraded"),
    DOWN("down"),
    UNKNOWN("unknown"),
    OFF("off"),
    ;

    fun toHealthy(): Boolean? = when (this) {
        OK -> true
        DEGRADED, DOWN, OFF -> false
        UNKNOWN -> null
    }

    companion object {
        fun fromWire(value: String): StatusTrayState =
            entries.find { it.wire.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN

        fun fromHealthy(healthy: Boolean?): StatusTrayState = when (healthy) {
            true -> OK
            false -> DOWN
            null -> UNKNOWN
        }
    }
}

/** One domain row inside a [NodeStatusSnapshot]. Frozen `{ name, state, detail? }`. */
data class StatusTrayDomainStatus(
    val name: StatusTrayDomain,
    val state: StatusTrayState = StatusTrayState.UNKNOWN,
    val detail: String? = null,
    /** Original wire name when [name] is [StatusTrayDomain.OTHER]. Not a second domain. */
    val originalName: String? = null,
) {
    init {
        detail?.let {
            require(it.length <= MAX_DETAIL_CHARS) {
                "detail exceeds $MAX_DETAIL_CHARS characters"
            }
        }
    }

    fun wireName(): String =
        if (name == StatusTrayDomain.OTHER && !originalName.isNullOrBlank()) originalName else name.wire

    companion object {
        const val MAX_DETAIL_CHARS = 200
    }
}

/**
 * Frozen per-node status snapshot ([docs/specs/STATUS_TRAY.md]).
 *
 * Wire fields: [nodeId], [packageName], [connectionClass], [updatedAtMs],
 * [domains]. #233 aliases ([sourceNodeRef], [receivedAtMs], [domain],
 * [healthy]) stay on the JVM object so Settings UI and local backup keep
 * compiling.
 */
data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String? = null,
    val connectionClass: FleetIntegrationClass = FleetIntegrationClass.EXTERNAL_NODE,
    val updatedAtMs: Long,
    val domains: List<StatusTrayDomainStatus> = emptyList(),
    val capability: String = "status",
    val actionRef: String = "status",
    val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
        require(capability.isNotBlank()) { "capability is required" }
        packageName?.let { require(it.isNotBlank()) { "packageName must be blank or a real applicationId" } }
    }

    /** #233 alias — same string as [nodeId]. */
    val sourceNodeRef: String get() = nodeId

    /** #233 alias — controller ingest time; equals [updatedAtMs] on a fresh pull. */
    val receivedAtMs: Long get() = updatedAtMs

    /** #233 alias — first domain, or NODE_HEALTH when the list is empty. */
    val domain: StatusTrayDomain get() = domains.firstOrNull()?.name ?: StatusTrayDomain.NODE_HEALTH

    /**
     * #233 alias. `true` if any domain is `ok` and none is `down`;
     * `false` if any domain is `down`; otherwise `null`.
     */
    val healthy: Boolean?
        get() {
            if (domains.isEmpty()) return null
            if (domains.any { it.state == StatusTrayState.DOWN }) return false
            if (domains.any { it.state == StatusTrayState.OK }) return true
            return domains.mapNotNull { it.state.toHealthy() }.firstOrNull()
        }

    fun isFresh(nowMs: Long, freshMs: Long = FRESH_MS): Boolean = nowMs - updatedAtMs <= freshMs

    fun isExpired(nowMs: Long, expireMs: Long = EXPIRE_MS): Boolean = nowMs - updatedAtMs > expireMs

    companion object {
        const val FRESH_MS = 30_000L
        const val STALE_MS = 30_000L
        const val EXPIRE_MS = 300_000L
        const val RECOMMENDED_PULL_MS = 15_000L

        fun of(
            sourceNodeRef: String,
            domain: StatusTrayDomain = StatusTrayDomain.NODE_HEALTH,
            capability: String = "status",
            healthy: Boolean? = null,
            actionRef: String = "status",
            payload: Map<String, String> = emptyMap(),
            receivedAtMs: Long,
            packageName: String? = null,
            connectionClass: FleetIntegrationClass = FleetIntegrationClass.EXTERNAL_NODE,
            detail: String? = null,
        ): NodeStatusSnapshot = NodeStatusSnapshot(
            nodeId = sourceNodeRef,
            packageName = packageName,
            connectionClass = connectionClass,
            updatedAtMs = receivedAtMs,
            domains = listOf(
                StatusTrayDomainStatus(
                    name = domain,
                    state = StatusTrayState.fromHealthy(healthy),
                    detail = detail,
                ),
            ),
            capability = capability,
            actionRef = actionRef,
            payload = payload,
        )
    }
}

interface RemoteStatusTray {
    fun consume(snapshot: NodeStatusSnapshot)
    fun publish(nowMs: Long? = null): List<NodeStatusSnapshot>
}

/**
 * In-memory status tray. Nodes **publish** a snapshot; the controller
 * **consumes** it and **aggregates** domains per [NodeStatusSnapshot.nodeId].
 * Lost on process death. No live Messenger bind. Rows older than
 * [NodeStatusSnapshot.EXPIRE_MS] are dropped on publish.
 */
class InMemoryRemoteStatusTray(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val expireMs: Long = NodeStatusSnapshot.EXPIRE_MS,
) : RemoteStatusTray {
    private val lock = Any()
    private val latest = LinkedHashMap<String, NodeStatusSnapshot>()

    override fun consume(snapshot: NodeStatusSnapshot) {
        NodeStatusSnapshotCodec.assertEncodable(snapshot)
        synchronized(lock) {
            val existing = latest.remove(snapshot.nodeId)
            latest[snapshot.nodeId] = merge(existing, snapshot)
            while (latest.size > maxEntries) {
                val oldest = latest.keys.first()
                latest.remove(oldest)
            }
        }
    }

    override fun publish(nowMs: Long?): List<NodeStatusSnapshot> = synchronized(lock) {
        if (nowMs != null) {
            val expired = latest.filterValues { it.isExpired(nowMs, expireMs) }.keys.toList()
            expired.forEach { latest.remove(it) }
        }
        latest.values.toList()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64

        fun merge(existing: NodeStatusSnapshot?, incoming: NodeStatusSnapshot): NodeStatusSnapshot {
            if (existing == null) return incoming
            val byName = LinkedHashMap<String, StatusTrayDomainStatus>()
            existing.domains.forEach { byName[it.wireName()] = it }
            incoming.domains.forEach { byName[it.wireName()] = it }
            val newer = if (incoming.updatedAtMs >= existing.updatedAtMs) incoming else existing
            val older = if (newer === incoming) existing else incoming
            return newer.copy(
                packageName = newer.packageName ?: older.packageName,
                domains = byName.values.toList(),
            )
        }
    }
}
