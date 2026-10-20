package ai.droidcommand.fleet.messenger

/**
 * Catalogued EXTERNAL_NODE members the pairing UI can show. Package names match
 * `:app` `FleetCatalog` / the manifest `<queries>` list; live ops are the node's
 * documented parameterless reads (`*.capabilities.get`, `*.status`) plus optional
 * fire-and-forget `*.up`.
 *
 * Nothing here is trusted: pairing still requires an explicit owner action that
 * copies the *observed* package + signing-cert SHA-256 into [MutablePairedNodeStore].
 */
data class ExternalNodeSpec(
    val id: String,
    val displayName: String,
    /** Identity stored as [PairedNode.nodeId] and sent as `target_node_id`. */
    val nodeId: String,
    /** Production package first, then debug / bench aliases the owner may actually have installed. */
    val packages: List<String>,
    val capabilitiesOperation: String,
    val statusOperation: String,
    /** Null when this product has no start/bring-up op (Keyboard, File Manager stub). */
    val upOperation: String? = null,
    val notes: String = "",
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(packages.isNotEmpty()) { "packages must not be empty" }
        require(packages == packages.distinct()) { "duplicate package in $id" }
        require(capabilitiesOperation.isNotBlank() && statusOperation.isNotBlank()) { "read ops must not be blank" }
        upOperation?.let { require(NodeOperations.isFireAndForget(it)) { "upOperation must be a *.up name, not $it" } }
    }

    val primaryPackage: String get() = packages.first()

    fun readOnlyOperations(): Set<String> = setOf(capabilitiesOperation, statusOperation)

    fun isReadOnly(operation: String): Boolean = operation in readOnlyOperations()

    fun isLiveAllowed(operation: String): Boolean = isReadOnly(operation) || operation == upOperation
}

object ExternalNodeCatalog {
    val ROOTFORGE = ExternalNodeSpec(
        id = "rootforge",
        displayName = "RootForge OS",
        nodeId = "rootforge",
        packages = listOf("ai.rootforge.os", "ai.rootforge.os.debug"),
        capabilitiesOperation = "rootforge.capabilities.get",
        statusOperation = "rootforge.status",
        upOperation = "rootforge.up",
        notes = "EXTERNAL_NODE. Messenger action ai.rootforge.os.action.BRIDGE. Never DependencyGuard. SSH is CLI/ISO fallback only.",
    )

    val FILE_MANAGER = ExternalNodeSpec(
        id = "file-manager",
        displayName = "Victorious File Manager",
        nodeId = "file-manager",
        packages = listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug"),
        capabilitiesOperation = "filemanager.capabilities.get",
        statusOperation = "filemanager.status",
        notes = "EXTERNAL_NODE. Messenger action ai.victorious.filemanager.action.BRIDGE. Never DependencyGuard. Never AIDL.",
    )

    val KEYBOARD = ExternalNodeSpec(
        id = "keyboard",
        displayName = "Victorious Keyboard",
        nodeId = "keyboard",
        packages = listOf("com.victorious.keyboard", "com.victorious.keyboard.debug", "com.victorious.keyboard.bench"),
        capabilitiesOperation = "keyboard.capabilities.get",
        statusOperation = "keyboard.status",
        notes = "EXTERNAL_NODE. Planned action com.victorious.keyboard.action.BRIDGE. Never DependencyGuard. Never forwards typed text or keystrokes.",
    )

    val MESH = ExternalNodeSpec(
        id = "mesh",
        displayName = "Victorious Mesh",
        nodeId = "mesh",
        packages = listOf("com.victorious.mesh"),
        capabilitiesOperation = "mesh.capabilities.get",
        statusOperation = "mesh.status",
        upOperation = "mesh.up",
        notes = "EXTERNAL_NODE. Planned action com.victorious.mesh.action.BRIDGE. Never DependencyGuard. " +
            "Mesh keeps its own release key. Node-id prefix ^mesh-[0-9a-f]{32}$.",
    )

    val BROWSER = ExternalNodeSpec(
        id = "browser",
        displayName = "Victorious Browser",
        nodeId = "browser",
        packages = listOf("com.victorious.browser"),
        capabilitiesOperation = "browser.capabilities.get",
        statusOperation = "browser.proxy.status",
        notes = "EXTERNAL_NODE. Planned action com.victorious.browser.action.BRIDGE. Never DependencyGuard. " +
            "Browser keeps its own release key (CI-only; [planned/unverified]). " +
            "Caller identity = Message.sendingUid. Node-id prefix ^browser-[0-9a-f]{32}$.",
    )

    val VICTOR_SMS = ExternalNodeSpec(
        id = "victorsms",
        displayName = "VictorSMS",
        nodeId = "victorsms",
        packages = listOf("com.victorious.sms"),
        capabilitiesOperation = "sms.capabilities.get",
        statusOperation = "sms.status",
        notes = "EXTERNAL_NODE. Confirmed action com.victorious.sms.action.BRIDGE, protocol_major 1, " +
            "what=1, inbound op sms.remote_command.forward (controller op remote.command). " +
            "debug==release package com.victorious.sms. Node-id prefix ^victorsms-[0-9a-f]{32}$. " +
            "Never DependencyGuard. Isolated from wipe/power-off/lock/flash/bootloader. " +
            "VictorSMS owns whitelist + code word; DCA owns commandId→action. trigger{} is " +
            "informational. Debug SHA-256 documented (alias androiddebugkey) but " +
            "[planned/pending-merge] unenforced — catalogue fail-open/unpinned. Release cert TBD. " +
            "TODO: enforce debug pin after scaffold merge. Fleet-messenger read ops " +
            "sms.capabilities.get / sms.status stay placeholders. The frozen remote-control op for " +
            "protocol minor 1 is capabilities.get (not a per-class field name).",
    )

    val VICTOR_SPOOF = ExternalNodeSpec(
        id = "victorspoof",
        displayName = "VictorSpoof",
        nodeId = "victorspoof",
        packages = listOf("com.victorious.spoof"),
        capabilitiesOperation = "spoof.capabilities.get",
        statusOperation = "spoof.status",
        notes = "EXTERNAL_NODE. Planned action com.victorious.spoof.action.BRIDGE. Novelty/prank " +
            "caller-ID. Isolated from wipe/power-off/lock/flash/bootloader. Never DependencyGuard. " +
            "Signing certs TBD — catalogue fail-open/unpinned. TODO: pin debug+release SHA-256.",
    )

    val VICTORIOUS_FLASH = ExternalNodeSpec(
        id = "victorious-flash",
        displayName = "Victorious Flash",
        nodeId = "victorious-flash",
        packages = listOf("com.victorious.flash.android"),
        capabilitiesOperation = "capabilities.get",
        statusOperation = "status.get",
        notes = "EXTERNAL_NODE. Confirmed action com.victorious.flash.android.FLEET_BRIDGE " +
            "(exported service; Message what=1 json envelope + replyTo, reply what=2). " +
            "Node-id prefix ^vfa-[0-9a-f]{32}$. Read ops status.get / capabilities.get. " +
            "Action ops netboot.serve.start / netboot.serve.stop / otg.write{deviceName}. " +
            "gadget.expose is DESTRUCTIVE (always confirmation_required + on-phone ERASE). " +
            "Debug cert enforced. Release cert TBD. Never DependencyGuard. Distinct from " +
            "the Linux SSH core-flash node (vf- prefix).",
    )

    val KYC_AUDITLAB = ExternalNodeSpec(
        id = "kyc-auditlab",
        displayName = "KYC-AuditLab",
        nodeId = "kyc-auditlab",
        packages = listOf("com.identityshield.companion"),
        capabilitiesOperation = "kyc.capabilities.get",
        statusOperation = "kyc.status",
        notes = "EXTERNAL_NODE companion APK. Planned action com.identityshield.companion.action.BRIDGE. " +
            "remote.command OFF-by-default. All PII ops remoteAllowed:false; controller never " +
            "remote-allows them. Isolated from wipe/power-off/lock/flash/bootloader. Never " +
            "DependencyGuard, never AIDL. Active fail-open debug SHA-256 is per-machine default " +
            "debug.keystore — [provisional/pending-stable-cert] unenforced. Pending permanent " +
            "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D " +
            "(KYC PR #29, unmerged) recorded not enforced; swap once #29 merges. " +
            "Hub must not call /scope admin.",
    )

    val all: List<ExternalNodeSpec> = listOf(
        ROOTFORGE,
        FILE_MANAGER,
        KEYBOARD,
        MESH,
        BROWSER,
        VICTOR_SMS,
        VICTOR_SPOOF,
        VICTORIOUS_FLASH,
        KYC_AUDITLAB,
    )

    fun byId(id: String): ExternalNodeSpec? = all.find { it.id == id }
}
