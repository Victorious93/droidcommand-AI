package ai.droidcommand.remotecontrol

/**
 * VictorSpoof actionRefs. Isolated from destructive classification: these
 * names never become WIPE / POWER_OFF / LOCK / `remote_destructive`, even
 * when the envelope's advisory [FleetRemoteEnvelope.destructive] is true
 * or a macro step is flagged destructive.
 *
 * Package, node-id, and signing certs are **[CONTRACT TBD]**. This registry
 * is the actionRef table only — it is not a `KnownFleet` member and does
 * not invent a package or fingerprint.
 */
object VictorSpoofRegistry {
    const val ID = "victorspoof"
    const val DISPLAY_NAME = "VictorSpoof"

    val ACTION_REFS: Set<String> = setOf(
        "spoof.profile.apply",
        "spoof.profile.clear",
        "spoof.status",
    )

    fun isRegistered(actionRef: String): Boolean =
        actionRef in ACTION_REFS || actionRef.startsWith("spoof.")

    fun entry(actionRef: String): VictorSpoofEntry? {
        if (!isRegistered(actionRef)) return null
        return VictorSpoofEntry(actionRef = actionRef)
    }
}

data class VictorSpoofEntry(val actionRef: String) {
    init {
        require(VictorSpoofRegistry.isRegistered(actionRef)) {
            "actionRef '$actionRef' is not a VictorSpoof registry entry"
        }
    }
}

/**
 * Independent classification of [actionRef]. Envelope `destructive` is
 * never an input. VictorSpoof registry entries are never destructive.
 */
object ActionRefClassifier {
    fun isDestructive(
        actionRef: String,
        mapping: RemoteCommandMapping? = null,
        macro: Macro? = null,
        stepFlag: Boolean = false,
    ): Boolean {
        if (VictorSpoofRegistry.isRegistered(actionRef)) return false
        if (mapping != null) return mapping.effectiveDestructive
        if (macro != null) return macro.hasDestructiveStep
        val lowered = actionRef.lowercase()
        if (looksLikeDefinedDestructive(lowered)) return true
        return stepFlag
    }

    fun stepIsDestructive(step: MacroStep): Boolean =
        isDestructive(step.actionRef, stepFlag = step.destructive)

    private fun looksLikeDefinedDestructive(lowered: String): Boolean =
        lowered == "wipe" ||
            lowered.contains("power_off") ||
            lowered.contains("power-off") ||
            lowered == "remote_lock" ||
            lowered.endsWith(".wipe") ||
            lowered.endsWith(".power_off") ||
            lowered.endsWith(".lock")
}
