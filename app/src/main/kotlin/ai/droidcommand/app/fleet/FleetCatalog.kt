package ai.droidcommand.app.fleet

import ai.droidcommand.security.EdlCompanionIds

/**
 * Package-probe roster for Tools → Fleet. This is the UI half of
 * `core-security.KnownFleet` plus HackerAI (already a bound companion,
 * not one of the nine room-fleet items).
 *
 * Lives in `:app` so the default JVM build does not have to compile the
 * AIDL module. Companion rows stay a PackageManager probe. EXTERNAL_NODE
 * rows are paired from Tools → Fleet (package + observed cert); that does
 * not make a member LIVE.
 */
enum class FleetKind {
    COMPANION_APK,
    TERMUX,
    ROOTFORGE,
    EXTERNAL_NODE,
    HOST_PROCESS,
}

data class FleetMember(
    val id: String,
    val name: String,
    val packageName: String?,
    val kind: FleetKind,
    val summary: String,
    /**
     * Additional Android applicationIds this member may be installed as
     * (debug / bench aliases). Named in `<queries>` so a paired debug APK
     * is visible; the Fleet screen still probes [packageName] only.
     */
    val extraPackages: List<String> = emptyList(),
) {
    /** [packageName] plus [extraPackages] — the packages this row catalogues. */
    val androidPackages: List<String>
        get() = listOfNotNull(packageName) + extraPackages
}

object FleetCatalog {
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"
    const val PENTESTSWARM_PACKAGE = "ai.pentestswarm.companion"
    const val TERMUX_PACKAGE = "com.termux"

    /** applicationId on EDL main after PR #8. */
    const val EDLCOMMANDER_PACKAGE = EdlCompanionIds.PACKAGE
    const val EDLCOMMANDER_BIND_ACTION = EdlCompanionIds.BIND_ACTION
    const val EDLCOMMANDER_BIND_PERMISSION = EdlCompanionIds.BIND_PERMISSION

    /**
     * EXTERNAL_NODE on-device package, inferred from action `ai.rootforge.os.action.BRIDGE`.
     * Probe + pairing only; SSH remains the CLI fallback.
     */
    const val ROOTFORGE_PACKAGE = "ai.rootforge.os"

    /** EXTERNAL_NODE. Probe only until the owner pairs package + signing cert. */
    const val FILEMANAGER_PACKAGE = "ai.victorious.filemanager"

    /** EXTERNAL_NODE. Probe only until the owner pairs package + signing cert. */
    const val KEYBOARD_PACKAGE = "com.victorious.keyboard"

    /** EXTERNAL_NODE. Probe only — do not bind Messenger. debug==release package. */
    const val MESH_PACKAGE = "com.victorious.mesh"

    /** EXTERNAL_NODE. Probe only — do not bind Messenger. */
    const val BROWSER_PACKAGE = "com.victorious.browser"

    /** EXTERNAL_NODE. Probe only. debug==release. Debug cert documented [planned/pending-merge], unenforced. */
    const val VICTORSMS_PACKAGE = "com.victorious.sms"

    /** EXTERNAL_NODE. Novelty/prank caller-ID. Isolated from destructive. Certs TBD (fail-open). */
    const val VICTORSPOOF_PACKAGE = "com.victorious.spoof"

    /** Debug applicationId for RootForge OS. Catalogued for Messenger pairing / trust checks. */
    const val ROOTFORGE_DEBUG_PACKAGE = "ai.rootforge.os.debug"

    /** Debug applicationId for Victorious File Manager. */
    const val FILEMANAGER_DEBUG_PACKAGE = "ai.victorious.filemanager.debug"

    /** Debug applicationId for Victorious Keyboard. */
    const val KEYBOARD_DEBUG_PACKAGE = "com.victorious.keyboard.debug"

    /** Benchmark applicationId for Victorious Keyboard. */
    const val KEYBOARD_BENCH_PACKAGE = "com.victorious.keyboard.bench"

    val HACKERAI = FleetMember(
        id = "hackerai",
        name = "HackerAI",
        packageName = HACKERAI_PACKAGE,
        kind = FleetKind.COMPANION_APK,
        summary = "Bound companion (already mapped). Subagent orchestration stays in core-hackerai; this build does not bind IHackerAIService.",
    )

    val PENTEST_SWARM = FleetMember(
        id = "pentestswarm",
        name = "Pentest-Swarm",
        packageName = PENTESTSWARM_PACKAGE,
        kind = FleetKind.COMPANION_APK,
        summary = "BOUND_COMPANION, PLANNED. Awaiting the base repo / one command-envelope service. Older REST/AIDL copies in this repo are not LIVE.",
    )

    val ROOTFORGE = FleetMember(
        id = "rootforge",
        name = "RootForge OS",
        packageName = ROOTFORGE_PACKAGE,
        kind = FleetKind.ROOTFORGE,
        summary = "EXTERNAL_NODE. Live APK path on their main is Messenger + shared JSON (ai.rootforge.os.action.BRIDGE, protocol_major 1). Pair package + observed signing cert on this screen to run read-only status/capabilities. SSH is CLI/ISO fallback only. Never DependencyGuard, never a shared signing key.",
        extraPackages = listOf(ROOTFORGE_DEBUG_PACKAGE),
    )

    val TERMUX = FleetMember(
        id = "termux",
        name = "Termux / VictorSuite",
        packageName = TERMUX_PACKAGE,
        kind = FleetKind.TERMUX,
        summary = "TERMUX_IPC. com.termux.RUN_COMMAND + RUN_COMMAND_PENDING_INTENT (ClientBackedTermuxExecutor), gated on VictorSuite PR #15. Not a new bound service. On-device Kali/msfconsole round-trip is UNVERIFIED.",
    )

    val EDL_COMMANDER = FleetMember(
        id = "edl-commander",
        name = "EDL-Commander",
        packageName = EDLCOMMANDER_PACKAGE,
        kind = FleetKind.COMPANION_APK,
        summary = "BOUND_COMPANION, PLANNED. Package ai.edlcommander.companion. AIDL IEdlCompanion + " +
            "bind action ai.edlcommander.companion.BIND + uses-permission " +
            "ai.edlcommander.companion.permission.BIND_COMPANION. Owns Sahara/Firehose. " +
            "Never device-verified. Do not mark host-tool-only.",
    )

    val KYC_AUDITLAB = FleetMember(
        id = "kyc-auditlab",
        name = "KYC-AuditLab",
        packageName = null,
        kind = FleetKind.HOST_PROCESS,
        summary = "HOST_PROCESS_TOOL, PLANNED. Simulator only, localhost only. Hub must not call /scope admin.",
    )

    val FILE_MANAGER = FleetMember(
        id = "file-manager",
        name = "Victorious File Manager",
        packageName = FILEMANAGER_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Messenger action ai.victorious.filemanager.action.BRIDGE " +
            "(protocol_major 1) already on their main. Generic client exists but is not started unless " +
            "this node is paired (package + signing cert). Never DependencyGuard, never a shared signing key. " +
            "Identity/default-off PR still landing.",
        extraPackages = listOf(FILEMANAGER_DEBUG_PACKAGE),
    )

    val KEYBOARD = FleetMember(
        id = "keyboard",
        name = "Victorious Keyboard",
        packageName = KEYBOARD_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Planned Messenger action com.victorious.keyboard.action.BRIDGE " +
            "(protocol_major 1). Generic client exists but is not started unless this node is paired " +
            "(package + signing cert). Never DependencyGuard, never a shared signing key. Never forwards " +
            "typed text or keystrokes. Direction (hub query vs keyboard slash-commands) is open.",
        extraPackages = listOf(KEYBOARD_DEBUG_PACKAGE, KEYBOARD_BENCH_PACKAGE),
    )

    val MESH = FleetMember(
        id = "mesh",
        name = "Victorious Mesh",
        packageName = MESH_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Planned Messenger action com.victorious.mesh.action.BRIDGE " +
            "(protocol_major 1). Generic client exists but is not started unless this node is paired " +
            "(package + signing cert). debug==release package com.victorious.mesh (no applicationIdSuffix). " +
            "Never DependencyGuard, never a shared signing key. Mesh keeps its own release key. " +
            "External control default OFF.",
    )

    val BROWSER = FleetMember(
        id = "browser",
        name = "Victorious Browser",
        packageName = BROWSER_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Planned Messenger action com.victorious.browser.action.BRIDGE " +
            "(Message what=1 request / what=2 response); this app does not bind it. " +
            "Never DependencyGuard, never a shared signing key. Browser keeps its own release key " +
            "(CI-only; [planned/unverified]). External control default OFF.",
    )

    val VICTOR_SMS = FleetMember(
        id = "victorsms",
        name = "VictorSMS",
        packageName = VICTORSMS_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED [planned/pending-merge]. Messenger action " +
            "com.victorious.sms.action.BRIDGE (Message what=1 request, protocol_major 1, inbound op " +
            "sms.remote_command.forward). debug==release package. Isolated from destructive paths. " +
            "Debug SHA-256 documented (alias androiddebugkey) but unenforced — catalogue " +
            "fail-open/unpinned. Release cert TBD. Never DependencyGuard, never a shared signing key. " +
            "SMS whitelist and code word live in VictorSMS; DCA owns commandId→action. trigger{} is " +
            "informational. External control default OFF + explicit consent. Node-id victorsms-<32 hex>.",
    )

    val VICTOR_SPOOF = FleetMember(
        id = "victorspoof",
        name = "VictorSpoof",
        packageName = VICTORSPOOF_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Novelty/prank caller-ID. Planned Messenger action " +
            "com.victorious.spoof.action.BRIDGE. Isolated from wipe/power-off/lock/flash/bootloader. " +
            "Signing certs TBD — catalogue fail-open/unpinned. TODO pin debug+release SHA-256. " +
            "Never DependencyGuard, never a shared signing key.",
    )

    val all: List<FleetMember> = listOf(
        HACKERAI,
        PENTEST_SWARM,
        ROOTFORGE,
        TERMUX,
        EDL_COMMANDER,
        KYC_AUDITLAB,
        FILE_MANAGER,
        KEYBOARD,
        MESH,
        BROWSER,
        VICTOR_SMS,
        VICTOR_SPOOF,
    )

    /**
     * Every Android package the catalog names: Fleet-screen probes plus
     * Messenger EXTERNAL_NODE debug/bench aliases. Must match the
     * `<queries>` package list exactly (`ManifestPolicyTest`).
     */
    val queryPackages: Set<String>
        get() = all.flatMap { it.androidPackages }.toSet()
}

enum class FleetInstallState {
    INSTALLED,
    NOT_INSTALLED,
    NOT_APPLICABLE,
}

data class FleetRow(
    val member: FleetMember,
    val installed: FleetInstallState,
    val status: String,
)

object FleetStatus {
    fun resolve(members: List<FleetMember>, installed: (String) -> Boolean): List<FleetRow> =
        members.map { member ->
            val state = when (val pkg = member.packageName) {
                null -> FleetInstallState.NOT_APPLICABLE
                else -> if (installed(pkg)) FleetInstallState.INSTALLED else FleetInstallState.NOT_INSTALLED
            }
            FleetRow(member, state, statusLine(member, state))
        }

    private fun statusLine(member: FleetMember, state: FleetInstallState): String = when (member.kind) {
        FleetKind.COMPANION_APK -> when {
            member.id == "edl-commander" ->
                "AIDL IEdlCompanion copied. Bind action ai.edlcommander.companion.BIND. " +
                    "Fleet orchestrator does not auto-bind. Not LIVE."
            state == FleetInstallState.INSTALLED ->
                "Installed. AIDL bind is not started in this build, so its tools stay disconnected."
            else ->
                "Not installed. Install the companion APK before any later bind work can succeed."
        }
        FleetKind.TERMUX -> when (state) {
            FleetInstallState.INSTALLED ->
                "Installed. RUN_COMMAND uses RUN_COMMAND_PENDING_INTENT. On-device Kali/msfconsole round-trip is UNVERIFIED."
            else ->
                "Not installed. After install, RUN_COMMAND still needs allow-external-apps=true and the RUN_COMMAND permission."
        }
        FleetKind.ROOTFORGE,
        FleetKind.EXTERNAL_NODE,
        -> when (state) {
            FleetInstallState.INSTALLED ->
                "Installed. Pair the observed signing-cert SHA-256 on this screen to run read-only Messenger status/capabilities. Writes stay gated. Not LIVE."
            FleetInstallState.NOT_INSTALLED ->
                "Not installed. Messenger transport exists but this node is not paired. Not LIVE."
            FleetInstallState.NOT_APPLICABLE ->
                "No APK on this row. Pair package + cert to enable Messenger. Not LIVE."
        }
        FleetKind.HOST_PROCESS ->
            "Not started. Simulator only, localhost only. The hub must not call /scope admin. Not LIVE."
    }
}
