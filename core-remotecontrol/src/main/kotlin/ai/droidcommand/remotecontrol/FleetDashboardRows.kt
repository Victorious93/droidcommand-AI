package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetMemberDescriptor
import ai.droidcommand.security.KnownFleet

/**
 * One catalogued fleet member as the home dashboard wants it: a name, a
 * one-line role, and enough ids to match pairings and tray snapshots.
 *
 * This is not a second catalog. [FleetDashboardCatalog] reads
 * [KnownFleet] plus HackerAI. The Android screen maps `:app` `FleetCatalog`
 * onto the same shape.
 */
data class FleetDashboardMember(
    val id: String,
    val name: String,
    val role: String,
    val packageNames: List<String> = emptyList(),
    val nodeIdHints: List<String> = emptyList(),
    val highlighted: Boolean = false,
    val opensPairing: Boolean = false,
    /** Controller transport is on disk but has never run on a device. */
    val pendingDeviceTest: Boolean = false,
    /** Live package is still `com.termux`; rename not switched yet. */
    val pendingRepackage: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "id is required" }
        require(name.isNotBlank()) { "name is required" }
        require(role.isNotBlank()) { "role is required" }
    }
}

/** A saved pairing from Messenger or Settings. Package or node id is enough to match. */
data class FleetDashboardPairing(
    val nodeId: String,
    val packageName: String? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        packageName?.let { require(it.isNotBlank()) { "packageName must be blank or a real applicationId" } }
    }
}

enum class FleetDashboardTone {
    /** Gray. Not paired, or paired with nothing useful to show. */
    UNKNOWN,

    /** Green. A *fresh* snapshot says this node is ok. Stale snapshots must not use this. */
    OK,

    /** Amber. A snapshot says this node is struggling. */
    DEGRADED,

    /** Red. A snapshot says this node is down. */
    DOWN,
}

data class FleetDashboardRow(
    val member: FleetDashboardMember,
    val paired: Boolean,
    val tone: FleetDashboardTone,
    val statusLabel: String,
    val seenLabel: String,
    val snapshot: NodeStatusSnapshot? = null,
)

data class FleetDashboardUi(
    val rows: List<FleetDashboardRow>,
    val liveBindReady: Boolean = false,
    val liveBindNote: String = FleetDashboardRows.LIVE_BIND_PENDING_NOTE,
)

/**
 * Catalogue + pairings + in-memory tray → one row per catalogued member.
 *
 * Honest about the missing live `node.status.get` bind: [LIVE_BIND_PENDING_NOTE]
 * is the only live-status claim this mapper makes. Refresh only republishes
 * the in-memory tray (TTL expire) and re-reads pairings.
 */
object FleetDashboardRows {
    const val LIVE_BIND_PENDING_NOTE =
        "Live status from the phone is not wired yet. This list uses saved pairings and any snapshots already in memory."

    const val STATUS_UNKNOWN = "Unknown"
    const val STATUS_NOT_PAIRED = "Not paired"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_PENDING_REPACKAGE = "PENDING-REPACKAGE"
    const val STATUS_AWAITING = "Awaiting status"
    const val STATUS_REACHABLE = "Reachable"
    const val STATUS_TROUBLE = "Having trouble"
    const val STATUS_DOWN = "Not reachable"
    const val SEEN_NONE = "No snapshot yet"

    fun build(
        members: List<FleetDashboardMember>,
        pairings: List<FleetDashboardPairing>,
        snapshots: List<NodeStatusSnapshot>,
        nowMs: Long,
    ): List<FleetDashboardRow> = members.map { member ->
        val paired = pairings.any { matchesPairing(member, it) }
        val snapshot = if (paired) {
            snapshots
                .filter { matchesSnapshot(member, it) && !it.isExpired(nowMs) }
                .maxByOrNull { it.updatedAtMs }
        } else {
            null
        }
        val tone = toneOf(paired, snapshot, nowMs)
        FleetDashboardRow(
            member = member,
            paired = paired,
            tone = tone,
            statusLabel = statusLabel(
                paired,
                snapshot,
                member.pendingDeviceTest,
                member.pendingRepackage,
                nowMs,
            ),
            seenLabel = seenLabel(snapshot, nowMs),
            snapshot = snapshot,
        )
    }

    fun toneOf(paired: Boolean, snapshot: NodeStatusSnapshot?, nowMs: Long): FleetDashboardTone {
        if (!paired || snapshot == null) return FleetDashboardTone.UNKNOWN
        // Stale (past FRESH_MS, still inside EXPIRE_MS) must not keep a green OK.
        if (!snapshot.isFresh(nowMs)) return FleetDashboardTone.UNKNOWN
        val states = snapshot.domains.map { it.state }
        return when {
            states.any { it == StatusTrayState.DOWN } -> FleetDashboardTone.DOWN
            states.any { it == StatusTrayState.DEGRADED } -> FleetDashboardTone.DEGRADED
            states.any { it == StatusTrayState.OK } -> FleetDashboardTone.OK
            else -> FleetDashboardTone.UNKNOWN
        }
    }

    fun statusLabel(
        paired: Boolean,
        snapshot: NodeStatusSnapshot?,
        pendingDeviceTest: Boolean = false,
        pendingRepackage: Boolean = false,
        nowMs: Long = 0L,
    ): String = when {
        pendingRepackage && snapshot == null -> STATUS_PENDING_REPACKAGE
        pendingDeviceTest && snapshot == null -> STATUS_PENDING
        !paired -> STATUS_NOT_PAIRED
        snapshot == null -> STATUS_AWAITING
        else -> when (toneOf(true, snapshot, nowMs)) {
            FleetDashboardTone.OK -> STATUS_REACHABLE
            FleetDashboardTone.DEGRADED -> STATUS_TROUBLE
            FleetDashboardTone.DOWN -> STATUS_DOWN
            FleetDashboardTone.UNKNOWN -> STATUS_UNKNOWN
        }
    }

    fun seenLabel(snapshot: NodeStatusSnapshot?, nowMs: Long): String {
        if (snapshot == null) return SEEN_NONE
        val age = (nowMs - snapshot.updatedAtMs).coerceAtLeast(0L)
        val stale = !snapshot.isFresh(nowMs)
        val whenText = when {
            age < 1_000L -> "Last seen just now"
            age < 60_000L -> "Last seen ${age / 1_000L}s ago"
            else -> "Last seen ${age / 60_000L} min ago"
        }
        return if (stale) "$whenText · stale" else whenText
    }

    fun matchesPairing(member: FleetDashboardMember, pairing: FleetDashboardPairing): Boolean {
        if (member.packageNames.any { it.equals(pairing.packageName, ignoreCase = true) }) return true
        return matchesNodeId(member, pairing.nodeId)
    }

    fun matchesSnapshot(member: FleetDashboardMember, snapshot: NodeStatusSnapshot): Boolean {
        if (member.packageNames.any { it.equals(snapshot.packageName, ignoreCase = true) }) return true
        return matchesNodeId(member, snapshot.nodeId)
    }

    private fun matchesNodeId(member: FleetDashboardMember, nodeId: String): Boolean {
        if (nodeId.equals(member.id, ignoreCase = true)) return true
        return member.nodeIdHints.any { hint ->
            nodeId.equals(hint, ignoreCase = true) ||
                nodeId.startsWith("$hint-", ignoreCase = true)
        }
    }
}

/**
 * Home-dashboard members from [KnownFleet] plus HackerAI (not one of the
 * thirteen room-fleet rows). Roles are everyday words for the UI.
 */
object FleetDashboardCatalog {
    const val HACKERAI_ID = "hackerai"
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"

    /** Nodes whose existing Tools → Fleet pairing screen can open. */
    val PAIRING_FLOW_IDS: Set<String> = setOf(
        "rootforge",
        "file-manager",
        "keyboard",
        "mesh",
        "browser",
        "victorsms",
        "victorspoof",
        "victorious-flash",
        "victorlaunch",
        "kyc-auditlab",
    )

    val HACKERAI: FleetDashboardMember = FleetDashboardMember(
        id = HACKERAI_ID,
        name = "HackerAI",
        role = roleFor(HACKERAI_ID),
        packageNames = listOf(HACKERAI_PACKAGE),
        nodeIdHints = listOf(HACKERAI_ID),
        opensPairing = false,
    )

    fun members(
        known: List<FleetMemberDescriptor> = KnownFleet.ALL,
        includeHackerAi: Boolean = true,
    ): List<FleetDashboardMember> {
        val knownMembers = known.map { fromKnown(it) }
        return if (includeHackerAi) listOf(HACKERAI) + knownMembers else knownMembers
    }

    fun fromKnown(member: FleetMemberDescriptor): FleetDashboardMember = FleetDashboardMember(
        id = member.id,
        name = displayName(member),
        role = roleFor(member.id),
        packageNames = packagesOf(member),
        nodeIdHints = nodeIdHints(member.id),
        highlighted = member.id == "rootforge",
        opensPairing = member.id in PAIRING_FLOW_IDS,
        pendingDeviceTest = member.id == "victorsuite" || member.id == "termux",
        pendingRepackage = member.id == "victorsuite" || member.id == "termux",
    )

    fun roleFor(id: String): String = when (id) {
        HACKERAI_ID -> "Extra AI helpers for harder jobs"
        "pentestswarm" -> "Runs security tests you approve"
        "rootforge" -> "Talks to a rooted phone and its gadgets"
        "victorsuite", "termux" -> "Linux tools on this phone"
        "edl-commander" -> "Talks to phones in EDL mode"
        "kyc-auditlab" -> "ID-check helper (this phone only)"
        "file-manager" -> "Files on this phone"
        "keyboard" -> "Custom keyboard"
        "mesh" -> "Private mesh network"
        "browser" -> "Private browser"
        "victorsms" -> "Delivers approved commands by text"
        "victorspoof" -> "Prank caller ID (not privileged)"
        "victorious-flash" -> "Flashing and gadget tools"
        "victorlaunch" -> "Launcher that reads fleet status"
        else -> "Fleet node"
    }

    fun displayName(member: FleetMemberDescriptor): String = when (member.id) {
        "victorsuite" -> "Termux / VictorSuite"
        "pentestswarm" -> "Pentest-Swarm"
        else -> member.displayName
    }

    fun packagesOf(member: FleetMemberDescriptor): List<String> {
        val extras = when (member.id) {
            "rootforge" -> listOf("ai.rootforge.os", "ai.rootforge.os.debug")
            "file-manager" -> listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug")
            "keyboard" -> listOf(
                "com.victorious.keyboard",
                "com.victorious.keyboard.debug",
                "com.victorious.keyboard.bench",
            )
            "victorsuite" -> listOf("com.termux")
            else -> emptyList()
        }
        return (member.androidPackages + extras).distinct()
    }

    fun nodeIdHints(id: String): List<String> = when (id) {
        "victorious-flash" -> listOf("victorious-flash", "vfa")
        "victorsuite" -> listOf("victorsuite", "termux")
        else -> listOf(id)
    }
}

/**
 * JVM-testable home-dashboard state. The Compose ViewModel is a thin wrapper.
 * [refresh] only republishes the in-memory tray and re-reads [pairings].
 * It does not bind Messenger or claim a live node.status.get.
 */
class FleetDashboardPresenter(
    private val members: List<FleetDashboardMember>,
    private val statusTray: RemoteStatusTray,
    private val pairings: () -> List<FleetDashboardPairing>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val liveBindReady: Boolean = false,
) {
    fun snapshot(): FleetDashboardUi {
        val nowMs = clock()
        return FleetDashboardUi(
            rows = FleetDashboardRows.build(
                members = members,
                pairings = pairings(),
                snapshots = statusTray.publish(nowMs),
                nowMs = nowMs,
            ),
            liveBindReady = liveBindReady,
            liveBindNote = if (liveBindReady) {
                "Live status from the phone is on."
            } else {
                FleetDashboardRows.LIVE_BIND_PENDING_NOTE
            },
        )
    }
}
