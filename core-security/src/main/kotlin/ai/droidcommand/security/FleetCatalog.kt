package ai.droidcommand.security

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * How DCA reaches one fleet member. These are the integration classes
 * `COMPANION_PROTOCOL.md` already defines, plus the two host/Termux
 * classes that must **not** be forced into AIDL + `DependencyGuard`.
 * See `docs/FLEET_CONTROL.md`.
 */
enum class FleetIntegrationClass {
    /** Dependent companion APK. AIDL + signature permission + DependencyGuard. */
    BOUND_COMPANION,

    /** Independent product. Authenticated SSH JSON (`core-rootforge`). Never DependencyGuard. */
    EXTERNAL_NODE,

    /** Termux / VictorSuite `com.termux.RUN_COMMAND`. Same package identity as Termux. */
    TERMUX_IPC,

    /** Local CLI or Python process. Never AIDL, never DependencyGuard. */
    HOST_PROCESS_TOOL,
}

/**
 * Catalog honesty grade — a documentation/registry axis, **not**
 * [CapabilityState]. [LIVE] is reserved for runtime-verified control.
 * None of [KnownFleet.ALL] is [LIVE].
 */
enum class FleetHonestyStatus {
    LIVE,
    PARTIAL,
    PLANNED,
    NULL_STUB,
}

/**
 * Static description of one room-fleet member. This is the JVM catalog
 * counterpart of `core-companion.CompanionDescriptor`: companions stay
 * in [KnownCompanions] (Android AIDL only); everyone else is listed here
 * so the hub can name them without inventing a second bind path.
 *
 * [usesAidl] / [usesDependencyGuard] must stay false unless
 * [integrationClass] is [FleetIntegrationClass.BOUND_COMPANION].
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
    val usesAidl: Boolean,
    val usesDependencyGuard: Boolean,
    val riskTier: RiskTier,
    val nextStep: String,
    val refusalMessage: String,
    val notes: String,
) {
    init {
        require(id.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) { "Invalid fleet member id: $id" }
        if (integrationClass != FleetIntegrationClass.BOUND_COMPANION) {
            require(!usesAidl && !usesDependencyGuard) {
                "$displayName is $integrationClass and must not use AIDL or DependencyGuard"
            }
        }
    }
}

/**
 * The five room-fleet members Don asked DCA to control, plus factory
 * helpers that register them on the existing [CapabilityRegistry] and
 * expose planned host-process [Tool] stubs.
 *
 * HackerAI (`ai.hackerai.companion`) is already a [BOUND_COMPANION] in
 * `KnownCompanions` / `core-hackerai`; it is not one of these five.
 */
object KnownFleet {
    val ROOTFORGE = FleetMemberDescriptor(
        id = "rootforge",
        displayName = "RootForge OS",
        repo = "Victorious93/rootforge-os",
        packageOrBinary = "ssh+json bridge (core-rootforge); no Android package",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PARTIAL,
        capabilityId = CapabilityId("fleet.rootforge.passive"),
        providerId = "rootforge",
        existingToolNames = listOf("rootforge_capabilities", "rootforge_list_devices"),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Keep the SSH JSON bridge. Do not add AIDL or DependencyGuard. " +
            "Next real work is an Android RootForgeTransport and, only after RootForge's own gates, write/build ops.",
        refusalMessage = "RootForge is an independent node. Pair it via DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE; " +
            "this catalog does not claim a live session.",
        notes = "Passive reads only (capabilities + device list). CLI registers the tools only when a nodes file is set.",
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
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Keep using core-termux / AdbTermuxExecutor. Do not add a companion AIDL. " +
            "VictorSuite-only features (multi-distro, backup/restore) need new surface area in VictorSuite itself.",
        refusalMessage = "VictorSuite is reached as Termux (com.termux RUN_COMMAND). " +
            "run_termux_command is the existing tool; this catalog does not add a second path.",
        notes = "Repo was documented in-tree (2026-09-29b) but was not publicly resolvable on GitHub as of 2026-10-14. " +
            "Shares Termux's applicationId; only one of Termux or VictorSuite can be installed.",
    )

    val KYC_AUDITLAB = FleetMemberDescriptor(
        id = "kyc-auditlab",
        displayName = "KYC-AuditLab",
        repo = null,
        packageOrBinary = "identityshield (Python host process); no Android package",
        integrationClass = FleetIntegrationClass.HOST_PROCESS_TOOL,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.kyc-auditlab.host"),
        providerId = "kyc-auditlab",
        existingToolNames = listOf("kyc_auditlab_status"),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Add a process-backed executor behind this status tool once the lab repo/binary is pinned. " +
            "Never AIDL or DependencyGuard. Do not log identity-document contents.",
        refusalMessage = "KYC-AuditLab is PLANNED. No Python IdentityShield process is configured. " +
            "This tool reports REQUIRES_EXTERNAL_SERVICE and does not run the lab.",
        notes = "No public repo under Victorious93/KYC-AuditLab (or kyc-auditlab) as of 2026-10-14.",
    )

    val EDL_COMMANDER = FleetMemberDescriptor(
        id = "edl-commander",
        displayName = "EDL-Commander",
        repo = null,
        packageOrBinary = "edl-commander (Rust Sahara/EDL CLI); no Android package",
        integrationClass = FleetIntegrationClass.HOST_PROCESS_TOOL,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.edl-commander.host"),
        providerId = "edl-commander",
        existingToolNames = listOf("edl_commander_status"),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "When the CLI is pinned, wire a dry-run-only status/probe executor. " +
            "Flash/write/restore stay unexposed until an explicit owner decision; those would be IRREVERSIBLE. " +
            "Never AIDL or DependencyGuard.",
        refusalMessage = "EDL-Commander is PLANNED. No Rust Sahara/EDL binary is configured. " +
            "This tool is status-only with dry-run defaults and does not flash, write, or restore.",
        notes = "No public repo under Victorious93/EDL-Commander (or edl-commander) as of 2026-10-14. Safety-first: no write tool is registered.",
    )

    val PENTEST_SWARM = FleetMemberDescriptor(
        id = "pentestswarm",
        displayName = "Pentest-Swarm-AI",
        repo = "Victorious93/Pentest-Swarm-AI",
        packageOrBinary = "ai.pentestswarm.companion",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        status = FleetHonestyStatus.PARTIAL,
        capabilityId = CapabilityId("fleet.pentestswarm.companion"),
        providerId = "pentestswarm",
        existingToolNames = listOf(
            "pentest_swarm_scan",
            "pentest_swarm_run_chain",
            "pentest_swarm_run_playbook",
        ),
        usesAidl = true,
        usesDependencyGuard = true,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Keep IPentestSwarmService + DependencyGuard. Next step is a same-key signed bind on a real device " +
            "(AIDL has never been runtime-verified) and, optionally, registering SwarmTool in cli.",
        refusalMessage = "Pentest-Swarm companion APK is not assumed connected. " +
            "core-pentest-swarm tools fail until the APK and Go process are present.",
        notes = "JVM REST client is implemented (loopback :18080). Android AIDL bind is ON DISK, not device-verified.",
    )

    val ALL: List<FleetMemberDescriptor> = listOf(
        ROOTFORGE,
        VICTORSUITE,
        KYC_AUDITLAB,
        EDL_COMMANDER,
        PENTEST_SWARM,
    )

    fun member(id: String): FleetMemberDescriptor? = ALL.find { it.id == id }

    fun byCapability(id: CapabilityId): FleetMemberDescriptor? = ALL.find { it.capabilityId == id }

    /** Planned host-process stubs only. Members that already have real tools are not duplicated. */
    fun plannedHostTools(): List<Tool> = ALL
        .filter { it.integrationClass == FleetIntegrationClass.HOST_PROCESS_TOOL }
        .map { PlannedHostProcessTool(it) }

    fun registerCapabilities(
        registry: CapabilityRegistry,
        now: Long = System.currentTimeMillis(),
        checker: CapabilityHealthChecker = StaticFleetHealthChecker(),
    ) {
        for (member in ALL) {
            registry.register(checker.verify(member.capabilityId, member.providerId).let { meta ->
                if (meta.lastVerifiedAt == now) meta else meta.copy(lastVerifiedAt = now)
            })
        }
    }

    fun formatListing(): String = buildString {
        appendLine("DroidCommand AI room fleet — 5 members. None is LIVE.")
        appendLine("Integration classes come from COMPANION_PROTOCOL.md / docs/FLEET_CONTROL.md.")
        appendLine("AIDL + DependencyGuard apply only to BOUND_COMPANION.")
        appendLine()
        for (member in ALL) {
            val repo = member.repo ?: "(repo unpublished / not found on public GitHub as of 2026-10-14)"
            appendLine("${member.id}  ${member.integrationClass}  ${member.status}  $repo")
            appendLine("  package/binary: ${member.packageOrBinary}")
            appendLine("  tools: ${member.existingToolNames.joinToString()}")
            appendLine("  aidl=${member.usesAidl}  dependencyGuard=${member.usesDependencyGuard}")
            appendLine("  next: ${member.nextStep}")
            appendLine()
        }
        appendLine("HackerAI (ai.hackerai.companion) is a BOUND_COMPANION already in KnownCompanions; not one of these five.")
    }
}

/**
 * Catalog-declared health only. Never probes a process, APK, SSH node, or
 * network, and never returns [CapabilityState.AVAILABLE] — none of the
 * five members is LIVE.
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

    private fun catalogState(member: FleetMemberDescriptor): CapabilityState = when (member.integrationClass) {
        FleetIntegrationClass.TERMUX_IPC -> CapabilityState.REQUIRES_TERMUX
        FleetIntegrationClass.EXTERNAL_NODE -> CapabilityState.REQUIRES_CONFIGURATION
        FleetIntegrationClass.BOUND_COMPANION,
        FleetIntegrationClass.HOST_PROCESS_TOOL,
        -> CapabilityState.REQUIRES_EXTERNAL_SERVICE
    }
}

/**
 * Honest stub for a [FleetIntegrationClass.HOST_PROCESS_TOOL] that is not
 * wired yet. Always [ToolResult.Failure] — same convention as
 * `HackerAiTool` and `Null*` executors. Never spawns a process.
 */
class PlannedHostProcessTool(private val member: FleetMemberDescriptor) : Tool {
    init {
        require(member.integrationClass == FleetIntegrationClass.HOST_PROCESS_TOOL) {
            "PlannedHostProcessTool is only for HOST_PROCESS_TOOL members, not ${member.integrationClass}"
        }
        require(member.status == FleetHonestyStatus.PLANNED || member.status == FleetHonestyStatus.NULL_STUB) {
            "Refusing to stub ${member.id}: status is ${member.status}, not PLANNED/NULL_STUB"
        }
    }

    override val spec = ToolSpec(
        name = member.existingToolNames.single(),
        description = "${member.displayName} (${member.status}): ${member.refusalMessage}",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
    )

    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Failure(member.refusalMessage)
}
