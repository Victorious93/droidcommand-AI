package ai.droidcommand.remotecontrol

/**
 * VictorSMS as a `remote.command` EXTERNAL_NODE target / pairing-table
 * row, alongside [VictorSpoofRegistry].
 *
 * Status: **[planned/pending-merge]** — the VictorSMS scaffold PR is
 * still open/unmerged. Catalogue identity: package [PACKAGE],
 * `KnownFleet.VICTOR_SMS`. Inbound SMS-trigger source op is
 * [FORWARD_OP] (`sms.remote_command.forward`).
 *
 * VictorSMS owns SMS-side gating (sender whitelist + secret code word
 * in its own settings). DCA owns only the commandId → action mapping.
 * `trigger.sourceNumber` / `matchedKeyword` / `rawExtras` ride in the
 * informational [TriggerInfo] sub-object and are **never** used for
 * authorization.
 *
 * Isolated from privileged / destructive paths the same way VictorSpoof
 * is: catalogued ops never become WIPE / POWER_OFF / LOCK / flash /
 * bootloader / `remote_destructive`, even when the envelope's advisory
 * [FleetRemoteEnvelope.destructive] is true. A forwarded **commandId**
 * that maps to wipe/lock/etc. is still classified independently.
 *
 * Debug cert SHA-256 is **documented, not enforced** (`enforcePin =
 * false`, [suggestedPairing] leaves cert empty). Release cert TBD.
 * **TODO: enforce the debug pin after the scaffold PR merges; pin
 * release later. Do not invent fingerprints.**
 */
object VictorSmsRegistry {
    const val ID = "victorsms"
    const val DISPLAY_NAME = "VictorSMS"
    const val PACKAGE = FleetRemoteEnvelope.VICTOR_SMS_PACKAGE
    const val BRIDGE_ACTION = FleetRemoteEnvelope.VICTOR_SMS_BRIDGE_ACTION
    const val FORWARD_OP = FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD
    const val STATUS = "[planned/pending-merge]"

    /**
     * Documented debug SHA-256, alias `androiddebugkey`.
     * **Not enforced** until the VictorSMS scaffold PR merges.
     */
    const val DEBUG_CERT_SHA256 =
        "BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A"
    const val DEBUG_CERT_ALIAS = "androiddebugkey"
    const val ENFORCE_PIN = false

    val ACTION_REFS: Set<String> = setOf(FORWARD_OP)

    val TARGET: KnownRemoteTarget = KnownRemoteTarget(
        id = ID,
        displayName = DISPLAY_NAME,
        packageName = PACKAGE,
        bridgeAction = BRIDGE_ACTION,
        nodeIdPrefix = FleetRemoteEnvelope.VICTOR_SMS_NODE_ID_PREFIX,
        documentedDebugCertSha256 = DEBUG_CERT_SHA256,
        debugCertAlias = DEBUG_CERT_ALIAS,
        enforcePin = ENFORCE_PIN,
        status = STATUS,
        isolatedFromDestructive = true,
        cataloguedOps = ACTION_REFS,
        notes = "EXTERNAL_NODE inbound SMS-trigger source. Op $FORWARD_OP. " +
            "VictorSMS owns whitelist + code word; DCA owns commandId→action. " +
            "trigger{} is informational. Isolated from destructive paths. " +
            "Debug cert documented (alias $DEBUG_CERT_ALIAS) but unenforced. " +
            "Release cert TBD. $STATUS",
    )

    fun isRegistered(actionRef: String): Boolean =
        actionRef in ACTION_REFS || actionRef == FORWARD_OP

    fun entry(actionRef: String): VictorSmsEntry? {
        if (!isRegistered(actionRef)) return null
        return VictorSmsEntry(actionRef = actionRef)
    }

    fun suggestedPairing(sourceNodeRef: String): DevicePairing =
        TARGET.suggestedPairing(sourceNodeRef)
}

data class VictorSmsEntry(val actionRef: String) {
    init {
        require(VictorSmsRegistry.isRegistered(actionRef)) {
            "actionRef '$actionRef' is not a VictorSMS registry entry"
        }
    }
}
