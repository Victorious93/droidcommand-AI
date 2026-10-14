package ai.droidcommand.app.fleet

/**
 * The companion/external-node roster the Tools UI can show without pretending
 * [ai.droidcommand.companion.AndroidCompanionRegistry] is bound.
 *
 * Package names match `core-companion.KnownCompanions` and `COMPANION_PROTOCOL.md`.
 * This catalog lives in `:app` so the default JVM build does not have to compile
 * the AIDL module. It never reports a member as ready to run.
 */
enum class FleetKind {
    COMPANION_APK,
    TERMUX,
    ROOTFORGE,
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

    val HACKERAI = FleetMember(
        id = "hackerai",
        name = "HackerAI",
        packageName = HACKERAI_PACKAGE,
        kind = FleetKind.COMPANION_APK,
        summary = "AIDL companion. Subagent orchestration stays in core-hackerai; this build does not bind IHackerAIService.",
    )

    val PENTEST_SWARM = FleetMember(
        id = "pentestswarm",
        name = "Pentest-Swarm",
        packageName = PENTESTSWARM_PACKAGE,
        kind = FleetKind.COMPANION_APK,
        summary = "AIDL companion plus a loopback REST server on port 18080. This build does not bind IPentestSwarmService.",
    )

    val TERMUX = FleetMember(
        id = "termux",
        name = "Termux / VictorSuite",
        packageName = TERMUX_PACKAGE,
        kind = FleetKind.TERMUX,
        summary = "Reached through com.termux.RUN_COMMAND. VictorSuite uses the same package id. This build still uses NullTermuxExecutor.",
    )

    val ROOTFORGE = FleetMember(
        id = "rootforge",
        name = "RootForge OS",
        packageName = null,
        kind = FleetKind.ROOTFORGE,
        summary = "Independent host over SSH-carried JSON. Not a companion APK. This app has no Android SSH transport and no node file.",
    )

    val all: List<FleetMember> = listOf(HACKERAI, PENTEST_SWARM, TERMUX, ROOTFORGE)
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
        FleetKind.COMPANION_APK -> when (state) {
            FleetInstallState.INSTALLED ->
                "Installed. AIDL bind is not started in this build, so its tools stay disconnected."
            else ->
                "Not installed. Install the companion APK before any later bind work can succeed."
        }
        FleetKind.TERMUX -> when (state) {
            FleetInstallState.INSTALLED ->
                "Installed. This build still uses NullTermuxExecutor, so a run is refused."
            else ->
                "Not installed. After install, this build still refuses Termux runs until a real executor is wired."
        }
        FleetKind.ROOTFORGE ->
            "Not paired. Passive SSH reads exist only in the JVM/CLI client; the app does not open a node."
    }
}
