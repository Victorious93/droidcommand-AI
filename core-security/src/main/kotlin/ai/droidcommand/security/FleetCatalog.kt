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
     * Never DependencyGuard. RootForge, File Manager, Keyboard, and Mesh are this class.
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
 *
 * [androidPackages] / [expectedSigningCertSha256] are the controller-side
 * node-signature pin (see [NodeSignatureVerifier]). Empty expected certs
 * mean `[Unknown]` — do not invent fingerprints. The default
 * [MissingExpectedCertPolicy.ALLOW] then lets existing flows proceed.
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
    /**
     * Android applicationIds this member may be bound or commanded as.
     * Empty when there is no APK (KYC) or no single package in this repo
     * (RootForge). Debug / release / benchmark ids are listed separately.
     */
    val androidPackages: List<String> = emptyList(),
    /**
     * Expected APK signing-certificate SHA-256 fingerprints (colon-separated
     * hex). One or more, e.g. debug + release. Empty = `[Unknown]`.
     * Applies to every id in [androidPackages]; a match against any entry
     * is enough. Parsed at construction — invalid values fail closed.
     */
    val expectedSigningCertSha256: List<String> = emptyList(),
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
        for (fp in expectedSigningCertSha256) {
            SigningCertSha256.parse(fp)
                ?: throw IllegalArgumentException("$displayName has an invalid signing cert SHA-256: $fp")
        }
    }

    fun signingCertLabel(): String =
        if (expectedSigningCertSha256.isEmpty()) {
            "[Unknown]"
        } else {
            expectedSigningCertSha256.joinToString()
        }
}

/**
 * The eight room-fleet members. Wiring order (owner, 2026-10-14, File Manager
 * added 2026-10-15, Keyboard added 2026-10-17, Mesh added 2026-10-18): Pentest
 * (when the base repo exists) → RootForge Messenger deepen → VictorSuite
 * RUN_COMMAND → EDL companion APK → KYC last → File Manager (when their
 * identity/default-off PR lands; after the RootForge Messenger client exists)
 * → Keyboard (catalog only; direction of hub-query vs keyboard slash-commands
 * is open) → Mesh (catalog only; EXTERNAL_NODE, own signing keys).
 *
 * HackerAI is already a bound companion in `KnownCompanions`; not one of these eight.
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
            "Older JVM REST + multi-method AIDL in this repo is leftover from an earlier slice. " +
            "Controller-side DEBUG signing cert SHA-256 is catalogued (enforceable). " +
            "Shared fleet RELEASE 9E:A7:… is [planned/unverified] — not enforced " +
            "(no verified release signingConfig yet).",
        androidPackages = listOf("ai.pentestswarm.companion"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.PENTEST_SWARM_DEBUG_SHA256),
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
        nextStep = "Pair a node (package + signing-cert SHA-256) to use the on-device Messenger client " +
            "(core-fleet-messenger + :app). RootForge tools remain SSH-only. No DependencyGuard and no " +
            "shared signing key. SSH stays CLI/ISO fallback. Owns target-phone control (USB OTG, later " +
            "LAN/Tailscale). Does not own Termux/proot or Sahara — RootForge UI may call the EDL companion later.",
        refusalMessage = "RootForge is an independent node. Pair it (Messenger / localhost API / SSH fallback); " +
            "this catalog does not claim a live session.",
        notes = "Live APK path already exists on their main (Messenger + shared JSON). " +
            "core-rootforge still only speaks SSH (CLI). On-device Messenger is core-fleet-messenger + :app, " +
            "off until a node is paired. Never DependencyGuard, never shared signing key. " +
            "Controller-side DEBUG signing cert for ai.rootforge.os.debug is catalogued (enforceable). " +
            "No release key yet.",
        androidPackages = listOf("ai.rootforge.os.debug"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256),
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
            "Shares Termux's applicationId; only one of Termux or VictorSuite can be installed. " +
            "Controller-side DEBUG + VictorSuite-own RELEASE certs are catalogued (enforceable). " +
            "VictorSuite RELEASE is not the shared fleet key.",
        androidPackages = listOf("com.termux"),
        expectedSigningCertSha256 = listOf(
            KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
            KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
        ),
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
            "APK not landed; no AIDL in this repo yet. Controller-side DEBUG signing cert " +
            "SHA-256 is catalogued (enforceable). Shared fleet RELEASE 9E:A7:… is " +
            "[planned/unverified] — not enforced (no verified release signingConfig yet).",
        androidPackages = listOf("ai.edlcommander.companion"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256),
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
        notes = "Host tool. No public repo as of 2026-10-14. /scope admin is off-limits until they add auth. " +
            "No Android package, so no signing-cert pin.",
        androidPackages = emptyList(),
        expectedSigningCertSha256 = emptyList(),
    )

    val FILE_MANAGER = FleetMemberDescriptor(
        id = "file-manager",
        displayName = "Victorious File Manager",
        repo = "Victorious93/File-Manager",
        packageOrBinary = "ai.victorious.filemanager (debug: ai.victorious.filemanager.debug)",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.file-manager.node"),
        providerId = "file-manager",
        existingToolNames = listOf("filemanager_status"),
        catalogStubName = "filemanager_status",
        wiringOrder = 6,
        transports = listOf(
            "messenger+json envelope (their main: service ai.victorious.filemanager.fleet.FileManagerBridgeService, " +
                "action ai.victorious.filemanager.action.BRIDGE, protocol_major 1; " +
                "Message what=1 Bundle key request, reply what=2 Bundle key response)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but File Manager is not " +
            "paired and the catalog stub still does not bind. When their identity/default-off PR lands " +
            "(external control default OFF; caller identity = UID package + SHA-256 cert, allow only " +
            "ai.droidcommand.app and its .debug build), pair the node to call it. Never DependencyGuard, " +
            "never shared signing key.",
        refusalMessage = "Victorious File Manager is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Their main already exposes action ai.victorious.filemanager.action.BRIDGE (protocol_major 1, " +
            "dca_required:false, live:false stub ops). Identity/default-off PR is still landing. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge. Never DependencyGuard, never shared signing key, never " +
            "KnownCompanions, never BIND_* signature permission, never AIDL. Their main already has " +
            "Messenger + JSON stub ops (filemanager.capabilities.get / status / list). Every reply currently " +
            "live:false, mode:stub, dca_required:false. Default-off + caller allowlist still landing in their PR. " +
            "Controller-side DEBUG cert is [unstable/CI-secrets] " +
            "(KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS) — documented, " +
            "not enforced. CI injects from secrets and falls back to the generic Android debug " +
            "key without them. No release key yet. Packages stay fail-open.",
        androidPackages = listOf(
            "ai.victorious.filemanager",
            "ai.victorious.filemanager.debug",
        ),
        expectedSigningCertSha256 = emptyList(),
    )

    val KEYBOARD = FleetMemberDescriptor(
        id = "keyboard",
        displayName = "Victorious Keyboard",
        repo = "Victorious93/Victorious-Keyboard",
        packageOrBinary = "com.victorious.keyboard " +
            "(debug: com.victorious.keyboard.debug; benchmark: com.victorious.keyboard.bench)",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.keyboard.node"),
        providerId = "keyboard",
        existingToolNames = listOf("keyboard_status"),
        catalogStubName = "keyboard_status",
        wiringOrder = 7,
        transports = listOf(
            "messenger+json envelope (planned: service com.victorious.keyboard.fleet.KeyboardBridgeService, " +
                "action com.victorious.keyboard.action.BRIDGE, protocol_major 1, " +
                "dca_required:false, fleet_class external_node; " +
                "Message what=1 Bundle key request, reply what=2 Bundle key response)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Catalog only until Don decides direction (hub queries the keyboard vs the keyboard " +
            "surfaces fleet slash-commands). Generic Messenger client exists (core-fleet-messenger + :app) " +
            "but Keyboard is not paired and the catalog stub still does not bind. Never DependencyGuard, " +
            "never shared signing key. Never forward typed text or keystrokes.",
        refusalMessage = "Victorious Keyboard is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Planned action com.victorious.keyboard.action.BRIDGE (protocol_major 1, dca_required:false, " +
            "fleet_class external_node). Planned ops keyboard.capabilities.get / keyboard.status. " +
            "External control default OFF; allowlist ai.droidcommand.app and ai.droidcommand.app.debug; " +
            "errors external_control_disabled / unauthorized_caller. Never forwards typed text or keystrokes. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge and File Manager. Never DependencyGuard, never shared signing " +
            "key, never KnownCompanions, never BIND_* signature permission, never AIDL. Planned Messenger + " +
            "JSON ops keyboard.capabilities.get / keyboard.status. Never forward typed text or keystrokes. " +
            "Direction (hub queries keyboard vs keyboard surfaces fleet slash-commands) is undecided by Don. " +
            "Controller-side DEBUG cert is catalogued for com.victorious.keyboard and .debug (enforceable). " +
            "No release key yet. .bench is uncatalogued (fail-open) via KnownNodeSigningCatalog.KEYBOARD_BENCH.",
        androidPackages = listOf(
            "com.victorious.keyboard",
            "com.victorious.keyboard.debug",
            "com.victorious.keyboard.bench",
        ),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256),
    )

    val MESH = FleetMemberDescriptor(
        id = "mesh",
        displayName = "Victorious Mesh",
        repo = "Victorious93/victorious-mesh",
        packageOrBinary = "com.victorious.mesh (debug==release; no applicationIdSuffix)",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.mesh.node"),
        providerId = "mesh",
        existingToolNames = listOf(
            "mesh_capabilities",
            "mesh_status",
            "mesh_up",
            "mesh_down",
            "mesh_get_ip",
        ),
        catalogStubName = "mesh_status",
        wiringOrder = 8,
        transports = listOf(
            "messenger+json envelope (planned: action com.victorious.mesh.action.BRIDGE, protocol_major 1, " +
                "dca_required:false, fleet_class external_node; " +
                "Message what=1 Bundle key request, reply what=2 Bundle key response; " +
                "node-id prefix ^mesh-[0-9a-f]{32}$)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Catalog only. After the RootForge Messenger client exists, a Mesh Messenger client can " +
            "follow that pattern. External control default OFF. Do not implement a live bind in this " +
            "catalog slice. Never DependencyGuard, never a shared signing key. Mesh keeps its own " +
            "release key; do not assume the shared fleet key.",
        refusalMessage = "Victorious Mesh is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Planned action com.victorious.mesh.action.BRIDGE (protocol_major 1, dca_required:false, " +
            "fleet_class external_node). Planned ops mesh.capabilities.get / mesh.status / mesh.up / " +
            "mesh.down / mesh.ip.get. LLM tools mesh_capabilities / mesh_status / mesh_up / mesh_down / " +
            "mesh_get_ip. External control default OFF; allowlist ai.droidcommand.app and " +
            "ai.droidcommand.app.debug. Node-id prefix ^mesh-[0-9a-f]{32}$. Mesh keeps its own " +
            "release key (debug SHA-256 F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:" +
            "30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4). Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge, File Manager, and Keyboard. Never DependencyGuard, never " +
            "shared signing key, never KnownCompanions, never BIND_* signature permission, never AIDL. " +
            "debug==release package com.victorious.mesh (no applicationIdSuffix). Mesh keeps its own " +
            "release key; do not assume the shared fleet key. Planned Messenger + JSON ops " +
            "mesh.capabilities.get / mesh.status / mesh.up / mesh.down / mesh.ip.get. External control " +
            "default OFF. Node-id prefix ^mesh-[0-9a-f]{32}$. Controller-side DEBUG cert is catalogued " +
            "(KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256, enforceable). Mesh release is [Unknown].",
        androidPackages = listOf("com.victorious.mesh"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256),
    )

    val ALL: List<FleetMemberDescriptor> = listOf(
        PENTEST_SWARM,
        ROOTFORGE,
        VICTORSUITE,
        EDL_COMMANDER,
        KYC_AUDITLAB,
        FILE_MANAGER,
        KEYBOARD,
        MESH,
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
        appendLine("DroidCommand AI room fleet — 8 members. None is LIVE.")
        appendLine("Wiring order: Pentest (when repo exists) → RootForge Messenger → VictorSuite RUN_COMMAND → EDL companion → KYC last → File Manager (when their identity/default-off PR lands) → Keyboard (catalog only; direction open) → Mesh (catalog only; own signing keys).")
        appendLine("AIDL + DependencyGuard apply only to BOUND_COMPANION (Pentest, EDL). RootForge/File Manager/Keyboard/Mesh Messenger is not that.")
        appendLine()
        for (member in ALL) {
            val repo = member.repo ?: "(repo unpublished / not found on public GitHub as of 2026-10-14)"
            appendLine("${member.wiringOrder}. ${member.id}  ${member.integrationClass}  ${member.status}  $repo")
            appendLine("  package/binary: ${member.packageOrBinary}")
            appendLine("  transports: ${member.transports.joinToString(" | ")}")
            appendLine("  tools: ${member.existingToolNames.joinToString()}")
            appendLine("  aidl=${member.usesAidl}  dependencyGuard=${member.usesDependencyGuard}")
            val pkgs = if (member.androidPackages.isEmpty()) "(none)" else member.androidPackages.joinToString()
            appendLine("  androidPackages: $pkgs")
            appendLine("  signingCertSha256: ${member.signingCertLabel()}")
            appendLine("  next: ${member.nextStep}")
            appendLine()
        }
        appendLine("HackerAI (ai.hackerai.companion) is a BOUND_COMPANION already in KnownCompanions; not one of these eight.")
        appendLine("Controller-side node signature check: NodeSignatureVerifier (core-security). Default missing-expected policy is ALLOW.")
        appendLine("Catalogue is partial — fail-closed should be enabled only once remaining members and verified release pins are catalogued.")
        appendLine("Enforceable: DCA debug; Mesh debug (com.victorious.mesh); Browser debug (com.victorious.browser); Pentest debug; EDL debug; HackerAI debug+verified release 9E:A7; RootForge debug (ai.rootforge.os.debug); Keyboard debug (not .bench); VictorSuite debug + own release.")
        appendLine("HackerAI is the only member whose shared-fleet RELEASE 9E:A7:… is firsthand verified and enforced. DCA/Pentest/EDL release stays [planned/unverified]. Browser RELEASE is own-key [planned/unverified] (CI-only, cert TBD).")
        appendLine("File-Manager DEBUG is [unstable/CI-secrets] — documented, not enforced. victorious-flash / BakerPi / KYC are not catalogued.")
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
