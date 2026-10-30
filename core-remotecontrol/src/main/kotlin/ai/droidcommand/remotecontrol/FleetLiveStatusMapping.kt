package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass

/**
 * Plain-English connection reasons for a fleet dashboard row, plus helpers that
 * turn a live Messenger status/capabilities answer into a [NodeStatusSnapshot]
 * the tray can publish.
 *
 * Reasons stay short and owner-facing. They do not invent a live bind when none
 * ran; [REASON_AWAITING] covers "paired, no usable snapshot yet".
 */
object FleetLiveStatusMapping {
    const val REASON_NOT_PAIRED = "Not paired"
    const val REASON_PEER_CONTROL_OFF = "Peer control off"
    const val REASON_BIND_REFUSED_SIGNATURE = "Bind refused (signature)"
    const val REASON_BIND_REFUSED_CERT = "Bind refused (cert)"
    const val REASON_BRIDGE_MISSING = "Bridge missing on this APK build"
    const val REASON_AWAITING = "Awaiting live status"
    const val REASON_LIVE = "Live"

    const val EMPTY_STATE_CTA =
        "Nothing is paired yet. Open Pair and manage nodes, confirm the signing cert on the phone, then tap Refresh list."

    fun reasonFor(
        paired: Boolean,
        snapshot: NodeStatusSnapshot?,
        trustDetail: String? = null,
        liveDetail: String? = null,
        bridgeMissing: Boolean = false,
        peerControlOff: Boolean = false,
        signatureRefused: Boolean = false,
        certRefused: Boolean = false,
    ): String {
        if (!paired) return REASON_NOT_PAIRED
        if (peerControlOff || looksLikePeerControlOff(liveDetail) || looksLikePeerControlOff(snapshot?.domains?.firstOrNull()?.detail)) {
            return REASON_PEER_CONTROL_OFF
        }
        if (signatureRefused || looksLikeSignatureRefusal(trustDetail) || looksLikeSignatureRefusal(liveDetail)) {
            return REASON_BIND_REFUSED_SIGNATURE
        }
        if (certRefused || looksLikeCertRefusal(trustDetail) || looksLikeCertRefusal(liveDetail)) {
            return REASON_BIND_REFUSED_CERT
        }
        if (bridgeMissing || looksLikeBridgeMissing(liveDetail) || looksLikeBridgeMissing(trustDetail)) {
            return REASON_BRIDGE_MISSING
        }
        if (snapshot != null && snapshot.healthy == true) return REASON_LIVE
        if (snapshot != null) {
            val detail = snapshot.domains.firstOrNull()?.detail
            classifyLiveDetail(detail)?.let { return it }
            return REASON_LIVE
        }
        return REASON_AWAITING
    }

    fun classifyLiveDetail(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        return when {
            looksLikePeerControlOff(detail) -> REASON_PEER_CONTROL_OFF
            looksLikeSignatureRefusal(detail) -> REASON_BIND_REFUSED_SIGNATURE
            looksLikeCertRefusal(detail) -> REASON_BIND_REFUSED_CERT
            looksLikeBridgeMissing(detail) -> REASON_BRIDGE_MISSING
            else -> null
        }
    }

    fun looksLikePeerControlOff(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return (
            t.contains("external control") || t.contains("peer control") || t.contains("remote.command") ||
                t.contains("remote command") || t.contains("action ops")
        ) &&
            (t.contains("off") || t.contains("disabled") || t.contains("not enabled") || t.contains("default"))
    }

    fun looksLikeSignatureRefusal(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return t.contains("signature") && (
            t.contains("refus") || t.contains("denied") || t.contains("mismatch") ||
                t.contains("permission") || t.contains("not allowed") || t.contains("pin")
        )
    }

    fun looksLikeCertRefusal(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return (t.contains("cert") || t.contains("signer") || t.contains("signing")) &&
            (t.contains("mismatch") || t.contains("refus") || t.contains("not the paired") || t.contains("expected"))
    }

    fun looksLikeBridgeMissing(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return t.contains("exposes no service") ||
            t.contains("bindservice refused") ||
            t.contains("no service for") ||
            t.contains("bridge missing") ||
            (t.contains("does not implement") && t.contains("bridge")) ||
            t.contains("returned no binder")
    }

    /**
     * Map a live Messenger status outcome string into a tray snapshot.
     * [ok] true → NODE_HEALTH OK; false → DOWN; null → UNKNOWN.
     */
    fun snapshotFromLive(
        nodeId: String,
        packageName: String?,
        ok: Boolean?,
        detail: String?,
        updatedAtMs: Long,
        actionRef: String = "status",
    ): NodeStatusSnapshot = NodeStatusSnapshot.of(
        sourceNodeRef = nodeId,
        domain = StatusTrayDomain.NODE_HEALTH,
        healthy = ok,
        actionRef = actionRef,
        receivedAtMs = updatedAtMs,
        packageName = packageName,
        connectionClass = FleetIntegrationClass.EXTERNAL_NODE,
        detail = detail?.take(StatusTrayDomainStatus.MAX_DETAIL_CHARS),
    )
}
