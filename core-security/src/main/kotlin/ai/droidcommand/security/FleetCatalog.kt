package ai.droidcommand.security

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * How DCA reaches one fleet member. AIDL + `DependencyGuard` apply only
 * to [FleetIntegrationClass.BOUND_COMPANION]. RootForge may use a bound
 * Messenger on the APK path and still must not get DependencyGuard.
 * See `docs/FLEET_CONTROL.md`.
 */
enum class FleetIntegrationClass {
    /** Dependent companion APK. AIDL + signature permission + DependencyGuard. */
    BOUND_COMPANION,

    /**
     * Independent product. Live APK = Messenger + shared JSON envelope;
     * desktop/Docker = localhost API + bearer; SSH = CLI/ISO fallback.
     * Never DependencyGuard.
     */
    EXTERNAL_NODE,

    /** Termux / VictorSuite `com.termux.RUN_COMMAND` + plugin IPC. Not a new bound service. */
    TERMUX_IPC,

    /** Localhost CLI/API process. Never AIDL, never DependencyGuard. */
    HOST_PROCESS_TOOL,
}

/**
 * Catalog honesty grade — not [CapabilityState]. [LIVE] is reserved for
 * runtime-verified control. None of [KnownFleet.ALL] is [LIVE].
 */
enum class FleetHonestyStatus {
    LIVE,
    PARTIAL,
    PLANNED,
    NULL_STUB,
}

/**
 * Static description of one room-fleet member. Companion APKs that use
 * AIDL + DependencyGuard are also listed in `KnownCompanions`; everyone
 * else must not be added there.
 *
 * [usesAidl] / [usesDependencyGuard] are true only for
 * [FleetIntegrationClass.BOUND_COMPANION]. RootForge's planned Messenger
 * bind is not that pair.
 */
data class FleetMemberDescriptor(
    val id: String,
    val displayName: String,
    val repo: String?,
    val packageOrBinary: String,
    val integrationClass: FleetIntegrationClass,
    val status: FleetHonestyStatus,
    val capabilityId: CapabilityId,
    val providerId: String,
    val existingToolNames: List<String>,
    val catalogStubName: String?,
    val wiringOrder: Int,
    val transports: List<String>,
    val usesAidl: Boolean,
    val usesDependencyGuard: Boolean,
    val riskTier: RiskTier,
    val nextStep: String,
    val refusalMessage: String,
    val notes: String,
) {
    init {
        require(id.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) { "Invalid fleet member id: $id" }
        if (integrationClass == FleetIntegrationClass.BOUND_COMPANION) {
            require(usesAidl && usesDependencyGuard) {
                "$displayName is BOUND_COMPANION and must declare AIDL + DependencyGuard"
            }
        } else {
            require(!usesAidl && !usesDependencyGuard) {
                "$displayName is $integrationClass and must not use AIDL or DependencyGuard"
            }
        }
    }
}

/**
 * The five room-fleet members. Wiring order (owner, 2026-10-14):
 * Pentest (when the base repo exists) → RootForge Messenger deepen →
 * VictorSuite RUN_COMMAND → EDL companion APK → KYC last.
 *
 * HackerAI is already a bound companion in `KnownCompanions`; not one of these five.
 */
object KnownFleet {
    val PENTEST_SWARM = FleetMemberDescriptor(
        id = "pentestswarm",
        displayName = "Pentest-Swarm-AI",
        repo = "Victorious93/Pentest-Swarm-AI",
        packageOrBinary = "ai.pentestswarm.companion",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.pentestswarm.companion"),
        providerId = "pentestswarm",
        existingToolNames = listOf("pentest_swarm_status"),
        catalogStubName = "pentest_swarm_status",
        wiringOrder = 1,
        transports = listOf("aidl-command-envelope (planned, single service)"),
        usesAidl = true,
        usesDependencyGuard = true,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "First when the base companion repo exists. One command-envelope AIDL service, " +
            "same-signing + DependencyGuard. This checkout already has older core-pentest-swarm / " +
            "IPentestSwarmService copies — do not treat those as LIVE control of the new envelope.",
        refusalMessage = "Pentest-Swarm is PLANNED — awaiting the base companion repo. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE. This stub does not bind or scan.",
        notes = "Room consensus 2026-10-14: PLANNED, no source for the envelope companion yet. " +
            "Older JVM REST + multi-method AIDL in this repo is leftover from an earlier slice.",
    )

    val ROOTFORGE = FleetMemberDescriptor(
        id = "rootforge",
        displayName = "RootForge OS",
        repo = "Victorious93/rootforge-os",
        packageOrBinary = "independent node; Messenger APK / localhost API / SSH fallback",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PARTIAL,
        capabilityId = CapabilityId("fleet.rootforge.passive"),
        providerId = "rootforge",
        existingToolNames = listOf("rootforge_capabilities", "rootforge_list_devices"),
        catalogStubName = null,
        wiringOrder = 2,
        transports = listOf(
            "messenger+json envelope (live APK path on rootforge-os main: action ai.rootforge.os.action.BRIDGE, protocol_major 1)",
            "localhost headless API + bearer (desktop/Docker, planned)",
            "ssh+json (CLI/ISO fallback only; what core-rootforge implements today)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Second: fill gaps so core-rootforge can call the existing Messenger bridge " +
            "(docs/DROIDCOMMAND_INTEGRATION.md on rootforge-os, action ai.rootforge.os.action.BRIDGE). " +
            "No DependencyGuard and no shared signing key. SSH stays fallback only. " +
            "Owns target-phone control (USB OTG, later LAN/Tailscale). Does not own Termux/proot " +
            "or Sahara — RootForge UI may call the EDL companion later for those.",
        refusalMessage = "RootForge is an independent node. Pair it (Messenger / localhost API / SSH fallback); " +
            "this catalog does not claim a live session.",
        notes = "Live APK path already exists on their main (Messenger + shared JSON). " +
            "This repo's core-rootforge still only speaks SSH. Never DependencyGuard, never shared signing key.",
    )

    val VICTORSUITE = FleetMemberDescriptor(
        id = "victorsuite",
        displayName = "VictorSuite",
        repo = "Victorious93/VictorSuite",
        packageOrBinary = "com.termux",
        integrationClass = FleetIntegrationClass.TERMUX_IPC,
        status = FleetHonestyStatus.PARTIAL,
        capabilityId = CapabilityId("fleet.victorsuite.run-command"),
        providerId = "termux",
        existingToolNames = listOf("run_termux_command"),
        catalogStubName = null,
        wiringOrder = 3,
        transports = listOf("termux RUN_COMMAND", "plugin IPC + shared command schema (planned)"),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Third: keep RUN_COMMAND / plugin IPC. Not a new bound service. " +
            "Gated on VictorSuite com.termux package/signing cleanup (their PR #15). " +
            "Owns on-device Termux/proot/X11/plugins.",
        refusalMessage = "VictorSuite is reached as Termux (com.termux RUN_COMMAND / plugin IPC). " +
            "run_termux_command is the existing tool; this catalog does not add a bound service.",
        notes = "Gated on Victorious93/VictorSuite PR #15 (package/signing). " +
            "Repo was not publicly resolvable on GitHub as of 2026-10-14. " +
            "Shares Termux's applicationId; only one of Termux or VictorSuite can be installed.",
    )

    val EDL_COMMANDER = FleetMemberDescriptor(
        id = "edl-commander",
        displayName = "EDL-Commander",
        repo = "Victorious93/EDL-Commander",
        packageOrBinary = "ai.edlcommander.companion (planned; APK has not landed)",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.edl-commander.companion"),
        providerId = "edl-commander",
        existingToolNames = listOf("edl_commander_status"),
        catalogStubName = "edl_commander_status",
        wiringOrder = 4,
        transports = listOf("aidl + same-signing (planned)", "USB 9008 / Sahara (in progress on their repo)"),
        usesAidl = true,
        usesDependencyGuard = true,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Fourth: wait for the companion APK, then IEDLCommanderService + BIND_EDLCOMMANDER " +
            "same-signing + DependencyGuard (like Pentest). Scaffold + AIDL + USB 9008/Sahara + " +
            "OnePlus 7T path is in progress on Victorious93/EDL-Commander. Do not add the AIDL file " +
            "here until that APK lands. Owns Sahara/Firehose. RootForge UI may call this later; " +
            "do not put flash in DCA or RootForge.",
        refusalMessage = "EDL-Commander companion APK has not landed. PLANNED. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE. This tool does not bind, flash, write, or restore.",
        notes = "Bound companion (AIDL + same-signing), like Pentest. Don explicitly chose this over host-tool-only. " +
            "APK not landed; no AIDL in this repo yet.",
    )

    val KYC_AUDITLAB = FleetMemberDescriptor(
        id = "kyc-auditlab",
        displayName = "KYC-AuditLab",
        repo = null,
        packageOrBinary = "identityshield simulator (localhost only); no Android package",
        integrationClass = FleetIntegrationClass.HOST_PROCESS_TOOL,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.kyc-auditlab.host"),
        providerId = "kyc-auditlab",
        existingToolNames = listOf("kyc_auditlab_status"),
        catalogStubName = "kyc_auditlab_status",
        wiringOrder = 5,
        transports = listOf("localhost simulator (no auth yet)"),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Last. Simulator only, localhost only. Do not call /scope admin until KYC adds auth. " +
            "Never AIDL. Do not log identity-document contents.",
        refusalMessage = "KYC-AuditLab is PLANNED. Simulator only, localhost only, no auth yet. " +
            "The hub must not call /scope admin. This tool does not run the lab.",
        notes = "Host tool. No public repo as of 2026-10-14. /scope admin is off-limits until they add auth.",
    )

    val ALL: List<FleetMemberDescriptor> = listOf(
        PENTEST_SWARM,
        ROOTFORGE,
        VICTORSUITE,
        EDL_COMMANDER,
        KYC_AUDITLAB,
    )

    fun member(id: String): FleetMemberDescriptor? = ALL.find { it.id == id }

    fun byCapability(id: CapabilityId): FleetMemberDescriptor? = ALL.find { it.capabilityId == id }

    /** Planned catalog stubs only. Members that already have real tools are not duplicated. */
    fun plannedStubs(): List<Tool> = ALL
        .filter { it.status == FleetHonestyStatus.PLANNED && it.catalogStubName != null }
        .map { PlannedFleetTool(it) }

    fun registerCapabilities(
        registry: CapabilityRegistry,
        now: Long = System.currentTimeMillis(),
        checker: CapabilityHealthChecker = StaticFleetHealthChecker(),
    ) {
        for (member in ALL) {
            val meta = checker.verify(member.capabilityId, member.providerId)
            registry.register(
                if (meta.lastVerifiedAt == now) meta else meta.copy(lastVerifiedAt = now),
            )
        }
    }

    fun formatListing(): String = buildString {
        appendLine("DroidCommand AI room fleet — 5 members. None is LIVE.")
        appendLine("Wiring order: Pentest (when repo exists) → RootForge Messenger → VictorSuite RUN_COMMAND → EDL companion → KYC last.")
        appendLine("AIDL + DependencyGuard apply only to BOUND_COMPANION (Pentest, EDL). RootForge Messenger is not that.")
        appendLine()
        for (member in ALL) {
            val repo = member.repo ?: "(repo unpublished / not found on public GitHub as of 2026-10-14)"
            appendLine("${member.wiringOrder}. ${member.id}  ${member.integrationClass}  ${member.status}  $repo")
            appendLine("  package/binary: ${member.packageOrBinary}")
            appendLine("  transports: ${member.transports.joinToString(" | ")}")
            appendLine("  tools: ${member.existingToolNames.joinToString()}")
            appendLine("  aidl=${member.usesAidl}  dependencyGuard=${member.usesDependencyGuard}")
            appendLine("  next: ${member.nextStep}")
            appendLine()
        }
        appendLine("HackerAI (ai.hackerai.companion) is a BOUND_COMPANION already in KnownCompanions; not one of these five.")
    }
}

/**
 * Catalog-declared health only. Never probes a process, APK, Messenger,
 * or network, and never returns [CapabilityState.AVAILABLE].
 */
class StaticFleetHealthChecker : CapabilityHealthChecker {
    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        val member = KnownFleet.byCapability(id)
            ?: KnownFleet.ALL.find { it.providerId == providerId }
            ?: throw UnknownCapabilityException(id)
        return CapabilityMetadata(
            id = member.capabilityId,
            providerId = member.providerId,
            version = "catalog-1",
            state = catalogState(member),
            permissionsRequired = emptyList(),
            dependencies = emptyList(),
            lastVerifiedAt = System.currentTimeMillis(),
            lastError = member.refusalMessage,
            description = "${member.displayName}: ${member.notes}",
            riskTier = member.riskTier,
        )
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = 30_000L

    private fun catalogState(member: FleetMemberDescriptor): CapabilityState =
        if (member.status == FleetHonestyStatus.PLANNED) {
            CapabilityState.REQUIRES_EXTERNAL_SERVICE
        } else {
            when (member.integrationClass) {
                FleetIntegrationClass.TERMUX_IPC -> CapabilityState.REQUIRES_TERMUX
                FleetIntegrationClass.EXTERNAL_NODE -> CapabilityState.REQUIRES_CONFIGURATION
                FleetIntegrationClass.BOUND_COMPANION,
                FleetIntegrationClass.HOST_PROCESS_TOOL,
                -> CapabilityState.REQUIRES_EXTERNAL_SERVICE
            }
        }
}

/**
 * Honest stub for a [FleetHonestyStatus.PLANNED] member. Always
 * [ToolResult.Failure]. Never binds and never spawns a process.
 */
class PlannedFleetTool(private val member: FleetMemberDescriptor) : Tool {
    init {
        require(member.status == FleetHonestyStatus.PLANNED || member.status == FleetHonestyStatus.NULL_STUB) {
            "Refusing to stub ${member.id}: status is ${member.status}, not PLANNED/NULL_STUB"
        }
        require(!member.catalogStubName.isNullOrBlank()) {
            "Refusing to stub ${member.id}: no catalogStubName"
        }
    }

    override val spec = ToolSpec(
        name = member.catalogStubName!!,
        description = "${member.displayName} (${member.status}): ${member.refusalMessage}",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
    )

    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Failure(member.refusalMessage)
}
