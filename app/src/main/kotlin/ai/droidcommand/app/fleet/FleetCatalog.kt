package ai.droidcommand.app.fleet

/**
 * Package-probe roster for Tools → Fleet. This is the UI half of
 * `core-security.KnownFleet` plus HackerAI (already a bound companion,
 * not one of the seven room-fleet items).
 *
 * Lives in `:app` so the default JVM build does not have to compile the
 * AIDL module. Install state is a PackageManager probe only. It never
 * reports a member as LIVE or ready to run.
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
)

object FleetCatalog {
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"
    const val PENTESTSWARM_PACKAGE = "ai.pentestswarm.companion"
    const val TERMUX_PACKAGE = "com.termux"

    /** Planned. The APK has not landed; do not bind this. */
    const val EDLCOMMANDER_PACKAGE = "ai.edlcommander.companion"

    /** EXTERNAL_NODE. Probe only — do not bind Messenger. */
    const val FILEMANAGER_PACKAGE = "ai.victorious.filemanager"

    /** EXTERNAL_NODE. Probe only — do not bind Messenger. */
    const val KEYBOARD_PACKAGE = "com.victorious.keyboard"

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
        packageName = null,
        kind = FleetKind.ROOTFORGE,
        summary = "EXTERNAL_NODE. Live APK path on their main is Messenger + shared JSON (ai.rootforge.os.action.BRIDGE, protocol_major 1). SSH is CLI/ISO fallback only. Never DependencyGuard, never a shared signing key.",
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
        summary = "BOUND_COMPANION, PLANNED. Owns Sahara/Firehose. AIDL + same-signing when the APK lands. Do not mark host-tool-only.",
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
            "(protocol_major 1) already on their main; this app does not bind it. " +
            "Never DependencyGuard, never a shared signing key. Identity/default-off PR still landing.",
    )

    val KEYBOARD = FleetMember(
        id = "keyboard",
        name = "Victorious Keyboard",
        packageName = KEYBOARD_PACKAGE,
        kind = FleetKind.EXTERNAL_NODE,
        summary = "EXTERNAL_NODE, PLANNED. Planned Messenger action com.victorious.keyboard.action.BRIDGE " +
            "(protocol_major 1); this app does not bind it. " +
            "Never DependencyGuard, never a shared signing key. Never forwards typed text or keystrokes. " +
            "Direction (hub query vs keyboard slash-commands) is open.",
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
    )
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
                "Companion APK has not landed. No AIDL bind. Not LIVE."
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
        FleetKind.ROOTFORGE ->
            "Not paired. Messenger + shared JSON is the live APK path on their main; this app does not bind it. SSH is fallback only, JVM/CLI."
        FleetKind.EXTERNAL_NODE -> when (state) {
            FleetInstallState.INSTALLED ->
                "Installed. Package probe only — Messenger bind is not started. PLANNED, not LIVE."
            FleetInstallState.NOT_INSTALLED ->
                "Not installed. Probe only — this build does not bind Messenger. PLANNED, not LIVE."
            FleetInstallState.NOT_APPLICABLE ->
                "No APK on this row. This build does not bind Messenger. Not LIVE."
        }
        FleetKind.HOST_PROCESS ->
            "Not started. Simulator only, localhost only. The hub must not call /scope admin. Not LIVE."
    }
}
