package ai.droidcommand.remotecontrol

/**
 * KYC-AuditLab companion as a `remote.command` EXTERNAL_NODE target /
 * pairing-table row, alongside [VictorSmsRegistry] and
 * [VictorSpoofRegistry].
 *
 * Status: **[provisional/pending-stable-cert]**. Package [PACKAGE]
 * (`com.identityshield.companion`). The **active** documented debug
 * SHA-256 ([DEBUG_CERT_SHA256]) is from a **per-machine default
 * debug.keystore** and is **not stable across machines**. Keep that pin
 * **fail-open** (`enforcePin = false`, [suggestedPairing] leaves cert
 * empty). **Do not enforce it.**
 *
 * [PENDING_STABLE_DEBUG_CERT_SHA256] is the **permanent** committed
 * debug-keystore fingerprint (reproducible). It lives in **KYC PR #29**,
 * which is **not merged yet**. Record it here; **do not swap** the
 * active pin and **do not enforce** it until that PR merges.
 *
 * `remote.command` is **OFF by default**. Every PII op is advertised
 * `remoteAllowed:false`. The controller never remote-allows PII ops
 * either ([forbidsRemote]). Isolated from wipe/power-off/lock/flash/
 * bootloader the same way VictorSpoof is.
 *
 * The localhost IdentityShield simulator (hub must not call `/scope`
 * admin) remains the KnownFleet host-process row. This registry is the
 * companion APK surface only. Never DependencyGuard, never AIDL.
 */
object KycAuditLabRegistry {
    const val ID = "kyc-auditlab"
    const val DISPLAY_NAME = "KYC-AuditLab"
    const val PACKAGE = FleetRemoteEnvelope.KYC_PACKAGE
    const val BRIDGE_ACTION = FleetRemoteEnvelope.KYC_BRIDGE_ACTION
    const val STATUS = "[provisional/pending-stable-cert]"
    const val CAPABILITIES_OP = "kyc.capabilities.get"
    const val STATUS_OP = "kyc.status"
    const val REMOTE_COMMAND_DEFAULT_OFF = true

    /**
     * Active fail-open debug SHA-256 from a per-machine default
     * debug.keystore. **Not stable. Not enforced.** Keep this as the
     * documented pin until KYC PR #29 merges.
     */
    const val DEBUG_CERT_SHA256 =
        "6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D"

    /**
     * Pending permanent debug SHA-256 from KYC's committed debug
     * keystore (reproducible). **KYC PR #29, not merged.** Recorded
     * only — **do not enforce. Swap the active pin once #29 merges.**
     */
    const val PENDING_STABLE_DEBUG_CERT_SHA256 =
        "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D"
    const val PENDING_STABLE_SOURCE = "KYC PR #29"
    const val ENFORCE_PIN = false

    val READ_OPS: Set<String> = setOf(CAPABILITIES_OP, STATUS_OP)

    val PII_EXAMPLE_OPS: Set<String> = setOf(
        "kyc.document.read",
        "kyc.document.scan",
        "kyc.identity.read",
        "kyc.pii.export",
        "kyc.session.capture",
    )

    val ACTION_REFS: Set<String> = READ_OPS + PII_EXAMPLE_OPS +
        setOf(FleetRemoteEnvelope.OP_REMOTE_COMMAND)

    val OPS: List<OpDescriptor> = listOf(
        OpDescriptor(
            name = CAPABILITIES_OP,
            kind = OpKind.READ,
            remoteAllowed = true,
            requiresConfirm = false,
        ),
        OpDescriptor(
            name = STATUS_OP,
            kind = OpKind.READ,
            remoteAllowed = true,
            requiresConfirm = false,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_REMOTE_COMMAND,
            kind = OpKind.ACTION,
            remoteAllowed = false,
            requiresConfirm = true,
        ),
    ) + PII_EXAMPLE_OPS.map { name ->
        OpDescriptor(
            name = name,
            kind = OpKind.ACTION,
            remoteAllowed = false,
            requiresConfirm = true,
        )
    }

    val TARGET: KnownRemoteTarget = KnownRemoteTarget(
        id = ID,
        displayName = DISPLAY_NAME,
        packageName = PACKAGE,
        bridgeAction = BRIDGE_ACTION,
        documentedDebugCertSha256 = DEBUG_CERT_SHA256,
        pendingPermanentDebugCertSha256 = PENDING_STABLE_DEBUG_CERT_SHA256,
        enforcePin = ENFORCE_PIN,
        status = STATUS,
        isolatedFromDestructive = true,
        cataloguedOps = ACTION_REFS,
        remoteCommandDefaultOff = REMOTE_COMMAND_DEFAULT_OFF,
        piiOpsNeverRemoteAllowed = true,
        notes = "EXTERNAL_NODE companion APK. remote.command OFF-by-default. " +
            "All PII ops remoteAllowed:false; controller never remote-allows them. " +
            "Isolated from destructive paths. Active fail-open debug cert is " +
            "per-machine debug.keystore ($DEBUG_CERT_SHA256) — documented, not " +
            "enforced. Pending permanent debug cert $PENDING_STABLE_DEBUG_CERT_SHA256 " +
            "($PENDING_STABLE_SOURCE, unmerged, committed debug keystore, " +
            "reproducible) — recorded, not enforced; swap the active pin once " +
            "#29 merges. $STATUS",
    )

    fun isRegistered(actionRef: String): Boolean =
        actionRef in ACTION_REFS || actionRef.startsWith("kyc.")

    fun isReadOp(actionRef: String): Boolean = actionRef in READ_OPS

    /** Fail-closed: every `kyc.*` op except capabilities/status is PII. */
    fun isPiiOp(actionRef: String): Boolean {
        if (isReadOp(actionRef)) return false
        return actionRef.startsWith("kyc.") || actionRef in PII_EXAMPLE_OPS
    }

    fun isKycNode(nodeRef: String): Boolean {
        val lowered = nodeRef.lowercase()
        return lowered == ID ||
            lowered == PACKAGE ||
            lowered.startsWith("kyc-") ||
            lowered.startsWith("kyc.")
    }

    /**
     * Controller-side gate. PII is never remote-allowed. `remote.command`
     * against a KYC node is OFF by default.
     */
    fun forbidsRemote(actionRef: String, targetNodeRef: String? = null): Boolean {
        if (isPiiOp(actionRef)) return true
        if (!REMOTE_COMMAND_DEFAULT_OFF) return false
        if (actionRef != FleetRemoteEnvelope.OP_REMOTE_COMMAND) return false
        return targetNodeRef != null && isKycNode(targetNodeRef)
    }

    fun descriptor(actionRef: String): OpDescriptor? = OPS.find { it.name == actionRef }

    fun entry(actionRef: String): KycAuditLabEntry? {
        if (!isRegistered(actionRef)) return null
        if (isPiiOp(actionRef)) return null
        if (actionRef == FleetRemoteEnvelope.OP_REMOTE_COMMAND && REMOTE_COMMAND_DEFAULT_OFF) return null
        return KycAuditLabEntry(actionRef = actionRef)
    }

    fun suggestedPairing(sourceNodeRef: String): DevicePairing =
        TARGET.suggestedPairing(sourceNodeRef)
}

data class KycAuditLabEntry(val actionRef: String) {
    init {
        require(KycAuditLabRegistry.isRegistered(actionRef)) {
            "actionRef '$actionRef' is not a KYC-AuditLab registry entry"
        }
        require(!KycAuditLabRegistry.isPiiOp(actionRef)) {
            "PII actionRef '$actionRef' is never remote-allowed on KYC-AuditLab"
        }
    }
}
