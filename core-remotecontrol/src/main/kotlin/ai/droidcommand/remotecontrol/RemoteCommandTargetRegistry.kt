package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass

/**
 * Known `remote.command` EXTERNAL_NODE targets / pairing-table rows.
 *
 * These are catalogue entries, not live pairings. A row does **not**
 * authorize inbound commands; [DevicePairing] still has to be saved, and
 * the trust pipeline still runs.
 *
 * [suggestedPairing] copies a pin from
 * [ai.droidcommand.security.KnownNodeSigningCatalog] /
 * [ai.droidcommand.security.FleetMemberDescriptor.expectedSigningCertSha256]
 * when one exists. Empty [KnownRemoteTarget.enforcePin] and UNENFORCED
 * documented constants (VictorSMS, KYC) are **not** a pin — those
 * packages stay unpinned here, same as a genuinely-unknown package.
 */
data class KnownRemoteTarget(
    val id: String,
    val displayName: String,
    val packageName: String,
    val bridgeAction: String,
    val nodeIdPrefix: String? = null,
    val integrationClass: FleetIntegrationClass = FleetIntegrationClass.EXTERNAL_NODE,
    val documentedDebugCertSha256: String? = null,
    val pendingPermanentDebugCertSha256: String? = null,
    val debugCertAlias: String? = null,
    val enforcePin: Boolean = false,
    val status: String,
    val isolatedFromDestructive: Boolean,
    val cataloguedOps: Set<String>,
    val remoteCommandDefaultOff: Boolean = false,
    val piiOpsNeverRemoteAllowed: Boolean = false,
    val notes: String,
) {
    init {
        require(id.isNotBlank()) { "id is required" }
        require(packageName.isNotBlank()) { "packageName is required" }
        require(bridgeAction.isNotBlank()) { "bridgeAction is required" }
        require(cataloguedOps.isNotEmpty()) { "cataloguedOps must not be empty" }
        if (enforcePin) {
            require(!documentedDebugCertSha256.isNullOrBlank()) {
                "enforcePin requires a documented debug cert"
            }
        }
    }

    /**
     * Pairing suggestion for this target. When the package has catalogued
     * expected certs, the first one is copied onto [DevicePairing.certSha256]
     * so a suggested row carries the pin. UNENFORCED documented fingerprints
     * and packages with an empty expected list stay `null`.
     */
    fun suggestedPairing(sourceNodeRef: String): DevicePairing = DevicePairing(
        sourceNodeRef = sourceNodeRef,
        packageName = packageName,
        certSha256 = suggestedCatalogPin(packageName),
    )
}

object RemoteCommandTargetRegistry {
    val ALL: List<KnownRemoteTarget>
        get() = listOf(
            VictorSmsRegistry.TARGET,
            VictorSpoofRegistry.TARGET,
            KycAuditLabRegistry.TARGET,
            VictorSuiteRegistry.TARGET,
        )

    fun byId(id: String): KnownRemoteTarget? = ALL.find { it.id == id }

    fun byPackage(packageName: String): KnownRemoteTarget? =
        ALL.find { it.packageName == packageName }
}
