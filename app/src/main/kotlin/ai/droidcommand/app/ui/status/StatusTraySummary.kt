package ai.droidcommand.app.ui.status

import ai.droidcommand.remotecontrol.NodeStatusSnapshot
import ai.droidcommand.remotecontrol.StatusTrayState

/** Plain-English lines for the Status & backup screen. Pure so it is unit tested. */
object StatusTraySummary {
    data class Row(val node: String, val headline: String, val freshness: String, val details: List<String>)

    fun rows(snapshots: List<NodeStatusSnapshot>, nowMs: Long): List<Row> = snapshots
        .filterNot { it.isExpired(nowMs) }
        .sortedBy { it.nodeId }
        .map { snap ->
            Row(
                node = snap.packageName ?: snap.nodeId,
                headline = headline(snap),
                freshness = freshness(snap, nowMs),
                details = snap.domains.map { d ->
                    val label = d.name.name.lowercase().replace('_', ' ')
                    val state = stateWord(d.state)
                    if (d.detail.isNullOrBlank()) "$label: $state" else "$label: $state (${d.detail})"
                },
            )
        }

    fun headline(snap: NodeStatusSnapshot): String = when (snap.healthy) {
        true -> "Working"
        false -> "Something is down"
        null -> "No health info yet"
    }

    fun freshness(snap: NodeStatusSnapshot, nowMs: Long): String {
        val ageSec = ((nowMs - snap.updatedAtMs).coerceAtLeast(0)) / 1000
        val age = when {
            ageSec < 60 -> "${ageSec}s ago"
            else -> "${ageSec / 60} min ago"
        }
        return if (snap.isFresh(nowMs)) "Updated $age" else "Out of date (updated $age)"
    }

    fun stateWord(state: StatusTrayState): String = when (state) {
        StatusTrayState.OK -> "ok"
        StatusTrayState.DEGRADED -> "struggling"
        StatusTrayState.DOWN -> "down"
        StatusTrayState.OFF -> "off"
        StatusTrayState.UNKNOWN -> "unknown"
    }

    /** Overall line for the top of the screen and the widget. */
    fun overall(snapshots: List<NodeStatusSnapshot>, nowMs: Long): String {
        val live = snapshots.filterNot { it.isExpired(nowMs) }
        if (live.isEmpty()) return "No fleet status yet"
        val down = live.count { it.healthy == false }
        return if (down == 0) "${live.size} device(s) reporting, nothing down" else "$down of ${live.size} device(s) have a problem"
    }
}
