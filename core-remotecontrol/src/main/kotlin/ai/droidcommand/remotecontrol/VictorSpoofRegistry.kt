package ai.droidcommand.remotecontrol

/**
 * VictorSpoof actionRefs. Isolated from privileged / destructive paths:
 * these names never become WIPE / POWER_OFF / LOCK / flash / bootloader /
 * `remote_destructive`, even when the envelope's advisory
 * [FleetRemoteEnvelope.destructive] is true or a macro step is flagged
 * destructive. Mapped actions stay non-destructive unless Don explicitly
 * maps otherwise.
 *
 * Catalogue identity: package [PACKAGE] (`EXTERNAL_NODE`),
 * `KnownFleet.VICTOR_SPOOF`. Signing certs are **[CONTRACT TBD]** —
 * fail-open / unpinned. **TODO: pin later. Do not invent fingerprints.**
 */
object VictorSpoofRegistry {
    const val ID = "victorspoof"
    const val DISPLAY_NAME = "VictorSpoof"
    const val PACKAGE = FleetRemoteEnvelope.VICTOR_SPOOF_PACKAGE
    const val BRIDGE_ACTION = FleetRemoteEnvelope.VICTOR_SPOOF_BRIDGE_ACTION

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
            lowered.endsWith(".lock") ||
            lowered.contains("bootloader") ||
            lowered == "flash" ||
            lowered.endsWith(".flash") ||
            lowered.contains("flash_")
}
