package ai.droidcommand.remotecontrol

/**
 * Fleet-Orchestrator-ratified error mapping for envelope [FleetRemoteEnvelope.targetNodeRef].
 *
 * - blank / malformed → [RemoteErrorCategory.INVALID_PARAMETERS]
 * - unknown / unresolvable → [RemoteErrorCategory.NOT_FOUND]
 * - known (catalog, stored target, or prefix) but unpaired → [RemoteErrorCategory.NOT_PAIRED]
 *
 * The literal [FleetRemoteEnvelope.TARGET_CONTROLLER] is always resolvable
 * (the hub itself) and does not need a [DevicePairing] row.
 *
 * This is a destination check. Source pairing still runs first in
 * [TrustPipeline] and is a separate identity gate.
 */
object TargetNodeRefValidator {
    val SHAPE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun classify(targetNodeRef: String, config: RemoteControlConfig): RemoteErrorCategory? {
        if (isMalformed(targetNodeRef)) {
            return RemoteErrorCategory.INVALID_PARAMETERS
        }
        if (targetNodeRef == FleetRemoteEnvelope.TARGET_CONTROLLER) {
            return null
        }
        if (!isKnown(targetNodeRef, config)) {
            return RemoteErrorCategory.NOT_FOUND
        }
        if (!isPaired(targetNodeRef, config)) {
            return RemoteErrorCategory.NOT_PAIRED
        }
        return null
    }

    fun rejectReason(category: RemoteErrorCategory, targetNodeRef: String): String = when (category) {
        RemoteErrorCategory.INVALID_PARAMETERS ->
            "malformed targetNodeRef"
        RemoteErrorCategory.NOT_FOUND ->
            "unknown targetNodeRef '$targetNodeRef'"
        RemoteErrorCategory.NOT_PAIRED ->
            "targetNodeRef '$targetNodeRef' is not paired"
        else -> category.wire
    }

    fun isMalformed(targetNodeRef: String): Boolean {
        if (targetNodeRef.isBlank()) return true
        if (targetNodeRef.trim() != targetNodeRef) return true
        if (!SHAPE.matches(targetNodeRef)) return true
        val prefix = FleetRemoteEnvelope.VICTOR_SMS_NODE_ID_PREFIX
        if (targetNodeRef.startsWith(prefix) &&
            !FleetRemoteEnvelope.VICTOR_SMS_NODE_ID.matches(targetNodeRef)
        ) {
            return true
        }
        return false
    }

    fun isKnown(targetNodeRef: String, config: RemoteControlConfig): Boolean {
        if (targetNodeRef == FleetRemoteEnvelope.TARGET_CONTROLLER) return true
        if (config.targets.any { it.nodeId == targetNodeRef }) return true
        if (config.pairings.any { it.sourceNodeRef == targetNodeRef }) return true
        if (RemoteCommandTargetRegistry.ALL.any { matchesCatalog(it, targetNodeRef) }) return true
        if (KycAuditLabRegistry.isKycNode(targetNodeRef)) return true
        return false
    }

    fun isPaired(targetNodeRef: String, config: RemoteControlConfig): Boolean {
        if (targetNodeRef == FleetRemoteEnvelope.TARGET_CONTROLLER) return true
        return config.pairings.any { it.sourceNodeRef == targetNodeRef }
    }

    private fun matchesCatalog(target: KnownRemoteTarget, ref: String): Boolean {
        if (ref.equals(target.id, ignoreCase = true)) return true
        if (ref.equals(target.packageName, ignoreCase = true)) return true
        val prefix = target.nodeIdPrefix
        return prefix != null && ref.startsWith(prefix)
    }
}
