package ai.droidcommand.app.fleet

import ai.droidcommand.companion.KnownCompanions
import ai.droidcommand.security.FleetHonestyStatus
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.FleetNode
import ai.droidcommand.security.FleetWiring
import ai.droidcommand.security.KnownFleet

/**
 * The Fleet Orchestrator's roster: exactly the members [FleetCatalog] already lists (HackerAI plus the
 * `KnownFleet` members, including Victorious Mesh and Victorious Browser). It adds no member of its own.
 */
object FleetNodes {
    /**
     * Wiring facts, each backed by code in this app today:
     * - Bound companions the registry will actually bind: HackerAI and Pentest-Swarm. EDL-Commander is deliberately
     *   absent (`AndroidCompanionRegistry` refuses it until its APK and AIDL exist).
     * - **2026-10-19:** the on-phone Messenger transport for external nodes (RootForge, File Manager, Keyboard,
     *   Mesh, Browser) is now wired — `FleetModule.provideFleetNodeTransport`'s EXTERNAL_NODE entry is
     *   `MessengerFleetTransport` over the real `core-fleet-messenger` `PairedNodeGateway`, not
     *   `UnwiredMessengerTransport`. An unpaired node still reports `PAIRING_REQUIRED`-equivalent "pairing
     *   required" from the transport's own probe; this flag only says a real path exists, not that any
     *   specific node is paired or has run on a device.
     * - Termux/VictorSuite RUN_COMMAND is wired for TermuxTool/Metasploit/Kali in `AppModule`, but the Fleet
     *   Orchestrator still has no Termux command channel (`TermuxFleetTransport.sendCommand` refuses).
     */
    val WIRING = FleetWiring(
        boundCompanionIds = setOf("hackerai", "pentestswarm"),
        messengerTransportWired = true,
        termuxRunnerWired = false,
    )

    fun nodes(): List<FleetNode> = FleetCatalog.all.map(::toNode)

    fun toNode(member: FleetMember): FleetNode = FleetNode(
        id = member.id,
        displayName = member.name,
        integrationClass = integrationClassOf(member.kind),
        status = honestyOf(member),
        packageName = member.packageName,
    )

    fun integrationClassOf(kind: FleetKind): FleetIntegrationClass = when (kind) {
        FleetKind.COMPANION_APK -> FleetIntegrationClass.BOUND_COMPANION
        FleetKind.TERMUX -> FleetIntegrationClass.TERMUX_IPC
        FleetKind.ROOTFORGE, FleetKind.EXTERNAL_NODE -> FleetIntegrationClass.EXTERNAL_NODE
        FleetKind.HOST_PROCESS -> FleetIntegrationClass.HOST_PROCESS_TOOL
    }

    /** HackerAI is not one of the seven `KnownFleet` members; its source exists but it has never run. The app's "termux" row is KnownFleet's VictorSuite. */
    private fun honestyOf(member: FleetMember): FleetHonestyStatus {
        val knownId = if (member.id == "termux") KnownFleet.VICTORSUITE.id else member.id
        return KnownFleet.member(knownId)?.status ?: FleetHonestyStatus.PARTIAL
    }

    /** The bound companions' package names the registry binds, for the manifest `<queries>` guard. */
    val BOUND_COMPANION_PACKAGES: Set<String> = setOf(
        KnownCompanions.HACKERAI_PACKAGE,
        KnownCompanions.PENTESTSWARM_PACKAGE,
    )
}
