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
     * Never DependencyGuard. RootForge, File Manager, Keyboard, Mesh, Browser, VictorSMS, VictorSpoof, Victorious Flash, and VictorLaunch are this class.
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
     * Empty when there is no APK or no single package in this repo.
     * KYC-AuditLab lists the companion APK `com.identityshield.companion`
     * (fail-open; pin unenforced). Debug / release / benchmark ids are
     * listed separately.
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
 * The thirteen room-fleet members. Wiring order (owner, 2026-10-14, File Manager
 * added 2026-10-15, Keyboard added 2026-10-17, Mesh added 2026-10-18,
 * Browser added 2026-10-18, VictorSMS added 2026-10-20, VictorSpoof added
 * 2026-10-20, Victorious Flash added 2026-10-20, VictorLaunch added
 * 2026-10-20): Pentest
 * (when the base repo exists) → RootForge Messenger deepen → VictorSuite
 * RUN_COMMAND → EDL companion APK → KYC last → File Manager (when their
 * identity/default-off PR lands; after the RootForge Messenger client exists)
 * → Keyboard (catalog only; direction of hub-query vs keyboard slash-commands
 * is open) → Mesh (catalog only; EXTERNAL_NODE, own signing keys)
 * → Browser (catalog only; EXTERNAL_NODE, own signing keys)
 * → VictorSMS (EXTERNAL_NODE, SMS remote-command source; debug cert
 * documented [planned/pending-merge] unenforced; release TBD / fail-open)
 * → VictorSpoof (EXTERNAL_NODE, novelty/prank caller-ID; isolated from
 * privileged/destructive paths; certs TBD / unpinned)
 * → Victorious Flash (EXTERNAL_NODE, Messenger FLEET_BRIDGE; gadget.expose
 * destructive always confirmation_required + on-phone ERASE; debug cert
 * enforced; release TBD)
 * → VictorLaunch (EXTERNAL_NODE, status-tray consumer + bridge node;
 * config.export/import; envelope v1.1; ACTION OPS OFF BY DEFAULT; debug
 * cert enforced; release TBD).
 * KYC-AuditLab's KnownFleet row stays HOST_PROCESS_TOOL (localhost
 * simulator). Its companion APK is a separate `remote.command`
 * EXTERNAL_NODE ([provisional/pending-stable-cert], fail-open).
 *
 * HackerAI is already a bound companion in `KnownCompanions`; not one of these thirteen.
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
        existingToolNames = listOf(
            "rootforge_capabilities", "rootforge_list_devices", "rootforge_device_info",
            "rootforge_adb_shell", "rootforge_adb_reboot", "rootforge_fastboot_reboot",
        ),
        catalogStubName = null,
        wiringOrder = 2,
        transports = listOf(
            "messenger+json envelope (live APK path on rootforge-os main: action ai.rootforge.os.action.BRIDGE, protocol_major 1)",
            "localhost headless API + bearer (desktop/Docker, planned)",
            "ssh+json (CLI/ISO fallback; what core-rootforge's cli tools speak today — 2026-10-19: deviceInfo/adbShell/adbReboot/fastbootReboot added alongside capabilities/listDevices)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "First P1 candidate. Hub Messenger client is live (FleetModule → MessengerFleetTransport → " +
            "PairedNodeGateway). Pair ai.rootforge.os / ai.rootforge.os.debug on Tools → Fleet, then Capabilities " +
            "(rootforge.capabilities.get) and Status. Hardware proof pending — no device run is claimed. " +
            "`cli`'s rootforge_* tools remain SSH-only (device-free CLI has no Android bind path). " +
            "No DependencyGuard and no shared signing key. SSH stays CLI/ISO fallback. Owns target-phone " +
            "control (USB OTG, later LAN/Tailscale). Does not own Termux/proot or Sahara — RootForge UI " +
            "may call the EDL companion later. File Manager stays PLANNED; do not use it as the P1 proof.",
        refusalMessage = "RootForge is an independent node. Pair it (Messenger / localhost API / SSH fallback); " +
            "this catalog does not claim a live session. Hardware proof pending.",
        notes = "First P1 candidate. Hub Messenger client is live (MessengerFleetTransport); hardware proof " +
            "pending. Live APK path already exists on their main (Messenger + shared JSON). " +
            "core-rootforge's cli tools speak SSH only. On-device Messenger is core-fleet-messenger + :app " +
            "(MessengerFleetTransport, 2026-10-19), off in practice until a node is paired on a real phone. Never " +
            "DependencyGuard, never shared signing key. Controller-side DEBUG signing cert for " +
            "ai.rootforge.os.debug is catalogued (enforceable). No release key yet.",
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
        existingToolNames = listOf("run_termux_command", "victorsuite_capabilities"),
        catalogStubName = null,
        wiringOrder = 3,
        transports = listOf(
            "termux RUN_COMMAND",
            "shared command schema (2026-10-19: VictorSuiteCapabilitiesProbe, read-only)",
            "TERMUX_IPC remote envelope v1 (BRIDGE_PROTOCOL 1.1): extra " +
                "ai.droidcommand.REMOTE_ENVELOPE_JSON on com.termux.RUN_COMMAND " +
                "(live default); pending com.victorious.suite.RUN_COMMAND behind " +
                "DROIDCOMMAND_VICTORSUITE_REPACKAGE (default off); " +
                "response ai.droidcommand.REMOTE_RESPONSE_JSON via PendingIntent; " +
                "ops capabilities.get, remote.command, macro.step, macro.run, " +
                "node.status.get, config.export, config.import; " +
                "PENDING-REPACKAGE — pending package rename / not yet switched " +
                "(VictorSuite PR #48 a6f4e2a; PR #51 draft)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Third: keep RUN_COMMAND / plugin IPC. Not a new bound service. " +
            "Controller-side TERMUX_IPC sender + response parser is on disk " +
            "(PENDING-REPACKAGE — pending package rename / not yet switched). " +
            "Live default stays com.termux. Flip DROIDCOMMAND_VICTORSUITE_REPACKAGE " +
            "only after FO go-signal + VictorSuite PR #51 merge + bootstrap rebuild. " +
            "Node side: VictorSuite PR #48 a6f4e2a; PR #51 is draft pending Don. " +
            "Gated on VictorSuite com.termux package/signing cleanup (their PR #15). " +
            "Owns on-device Termux/proot/X11/plugins. " +
            "2026-10-19: victorsuite_capabilities (core-termux) is a first read-only probe over this " +
            "same RUN_COMMAND path. External control OFF by default. Destructive and " +
            "config.import apply always confirmation_required and are not executed.",
        refusalMessage = "VictorSuite is reached as Termux (com.termux RUN_COMMAND / plugin IPC). " +
            "TERMUX_IPC remote envelope is PENDING-REPACKAGE — pending package rename / " +
            "not yet switched. Live default stays com.termux. " +
            "run_termux_command is the existing tool; this catalog does not add a bound service.",
        notes = "Gated on Victorious93/VictorSuite PR #15 (package/signing). " +
            "Controller-side TERMUX_IPC transport added 2026-10-20s against VictorSuite PR #48 " +
            "a6f4e2a (REMOTE_ENVELOPE_JSON / REMOTE_RESPONSE_JSON). " +
            "PENDING-REPACKAGE: VictorSuite PR #51 (draft, pending Don) will rename " +
            "applicationId to com.victorious.suite. Live default remains com.termux until " +
            "FO go-signal + bootstrap rebuild; flag DROIDCOMMAND_VICTORSUITE_REPACKAGE " +
            "(default off). Do not invent a fingerprint. " +
            "Repo was not publicly resolvable on GitHub as of 2026-10-14. " +
            "Shares Termux's applicationId today; only one of Termux or VictorSuite can be installed. " +
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
        packageOrBinary = "${EdlCompanionIds.PACKAGE} (AIDL copied; never device-bound from this repo)",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.edl-commander.companion"),
        providerId = "edl-commander",
        existingToolNames = listOf("edl_commander_status"),
        catalogStubName = "edl_commander_status",
        wiringOrder = 4,
        transports = listOf(
            "aidl ${EdlCompanionIds.AIDL_INTERFACE} + action ${EdlCompanionIds.BIND_ACTION}",
            "signature permission ${EdlCompanionIds.BIND_PERMISSION} (defined by EDL; hub uses-permission)",
            "USB 9008 / Sahara (in progress on their repo)",
        ),
        usesAidl = true,
        usesDependencyGuard = true,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Fourth: bind ${EdlCompanionIds.PACKAGE} with action ${EdlCompanionIds.BIND_ACTION} " +
            "and uses-permission ${EdlCompanionIds.BIND_PERMISSION} (IEdlCompanion, same-signing + " +
            "DependencyGuard like Pentest). AIDL is copied under ${EdlCompanionIds.AIDL_PACKAGE}. " +
            "The Fleet orchestrator still does not auto-bind — never device-verified. Owns " +
            "Sahara/Firehose. RootForge UI may call this later; do not put flash in DCA or RootForge.",
        refusalMessage = "EDL-Commander AIDL (IEdlCompanion) is copied; the companion has never been " +
            "bound from this repo. PLANNED. Capability state is REQUIRES_EXTERNAL_SERVICE. " +
            "This tool does not bind, flash, write, or restore.",
        notes = "Bound companion (AIDL + same-signing), like Pentest. Don explicitly chose this over host-tool-only. " +
            "applicationId on EDL main (PR #8) is ${EdlCompanionIds.PACKAGE}. " +
            "Controller-side DEBUG signing cert SHA-256 is catalogued " +
            "(enforceable, unchanged by the rename). Shared fleet RELEASE 9E:A7:… is " +
            "[planned/unverified] — not enforced (no verified release signingConfig yet).",
        androidPackages = listOf(EdlCompanionIds.PACKAGE),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256),
    )

    val KYC_AUDITLAB = FleetMemberDescriptor(
        id = "kyc-auditlab",
        displayName = "KYC-AuditLab",
        repo = null,
        packageOrBinary = "identityshield simulator (localhost) + companion APK com.identityshield.companion",
        integrationClass = FleetIntegrationClass.HOST_PROCESS_TOOL,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.kyc-auditlab.host"),
        providerId = "kyc-auditlab",
        existingToolNames = listOf("kyc_auditlab_status"),
        catalogStubName = "kyc_auditlab_status",
        wiringOrder = 5,
        transports = listOf(
            "localhost simulator (no auth yet; hub must not call /scope admin)",
            "messenger+json envelope (companion EXTERNAL_NODE: action " +
                "com.identityshield.companion.action.BRIDGE; remote.command OFF-by-default; " +
                "all PII ops remoteAllowed:false)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Last among the original five. Simulator only, localhost only. Do not call /scope admin " +
            "until KYC adds auth. Companion APK com.identityshield.companion is a remote.command " +
            "EXTERNAL_NODE [provisional/pending-stable-cert]: active fail-open debug cert is the " +
            "per-machine default debug.keystore (not stable). Pending permanent cert is in KYC PR #29 " +
            "(unmerged) — do not swap or enforce until it merges. remote.command OFF-by-default; " +
            "PII ops never remote-allowed from the controller either. Never AIDL. " +
            "Do not log identity-document contents.",
        refusalMessage = "KYC-AuditLab is PLANNED. Simulator only, localhost only, no auth yet. " +
            "The hub must not call /scope admin. Companion APK com.identityshield.companion is an " +
            "EXTERNAL_NODE remote.command target [provisional/pending-stable-cert] — fail-open, " +
            "active debug SHA-256 " +
            "6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D " +
            "documented not enforced (per-machine debug.keystore). Pending permanent " +
            "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D " +
            "(KYC PR #29, unmerged) recorded not enforced. remote.command OFF-by-default; " +
            "all PII ops remoteAllowed:false. This tool does not run the lab.",
        notes = "Host-process simulator PLUS companion EXTERNAL_NODE for remote.command. " +
            "Never DependencyGuard, never shared signing key, never KnownCompanions, never BIND_* " +
            "signature permission, never AIDL. Package com.identityshield.companion. " +
            "[provisional/pending-stable-cert]. Active fail-open debug SHA-256 " +
            "6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D " +
            "is per-machine default debug.keystore — documented, not enforced " +
            "(expectedSigningCertSha256 empty / fail-open). Pending permanent debug SHA-256 " +
            "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D " +
            "(KYC PR #29, unmerged, committed debug keystore, reproducible) — recorded, not " +
            "enforced; swap the active pin once #29 merges. remote.command OFF-by-default. " +
            "All PII ops remoteAllowed:false; controller never remote-allows them. " +
            "/scope admin is off-limits. Isolated from wipe/power-off/lock/flash/bootloader.",
        androidPackages = listOf("com.identityshield.companion"),
        expectedSigningCertSha256 = emptyList(),
    )

    val FILE_MANAGER = FleetMemberDescriptor(
        id = "file-manager",
        displayName = "VictorFiles",
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
        refusalMessage = "VictorFiles is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Their main already exposes action ai.victorious.filemanager.action.BRIDGE (protocol_major 1, " +
            "dca_required:false, live:false stub ops). Identity/default-off PR is still landing. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge. Never DependencyGuard, never shared signing key, never " +
            "KnownCompanions, never BIND_* signature permission, never AIDL. Their main already has " +
            "Messenger + JSON stub ops (filemanager.capabilities.get / status / list). Every reply currently " +
            "live:false, mode:stub, dca_required:false. Default-off + caller allowlist still landing in their PR. " +
            "Controller-side DEBUG cert is [unstable/CI-secrets] " +
            "(KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS) — documented, " +
            "not enforced on outbound Messenger (expectedSigningCertSha256 empty / fail-open) " +
            "because CI may fall back to the generic Android debug key. Inbound CODE_ASSIST " +
            "enforces the same pin via KnownNodeSigningCatalog.codeAssistCallerIdentities. " +
            "TODO: pin File-Manager RELEASE cert when one exists — do not invent. " +
            "No release key yet.",
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
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but Mesh is not " +
            "paired and the catalog stub still does not bind. Pair the node to call it. External " +
            "control default OFF. Never DependencyGuard, never a shared signing key. Mesh keeps its own " +
            "release key; do not assume the shared fleet key.",
        refusalMessage = "Victorious Mesh is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Planned action com.victorious.mesh.action.BRIDGE (protocol_major 1, dca_required:false, " +
            "fleet_class external_node). Planned ops mesh.capabilities.get / mesh.status / mesh.up / " +
            "mesh.down / mesh.ip.get. LLM tools mesh_capabilities / mesh_status / mesh_up / mesh_down / " +
            "mesh_get_ip. External control default OFF; allowlist ai.droidcommand.app and " +
            "ai.droidcommand.app.debug. Node-id prefix ^mesh-[0-9a-f]{32}$. Mesh keeps its own " +
            "release key (debug SHA-256 F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:" +
            "30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4; own RELEASE SHA-256 76:E9:15:83:31:40:DA:96:69:D3:CC:" +
            "5D:54:F6:66:A9:01:51:51:FC:F6:4C:59:B6:3D:DA:6E:D2:8F:83:A4:0D). Capability state is " +
            "REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge, File Manager, and Keyboard. Never DependencyGuard, never " +
            "shared signing key, never KnownCompanions, never BIND_* signature permission, never AIDL. " +
            "debug==release package com.victorious.mesh (no applicationIdSuffix). Mesh keeps its own " +
            "release key; do not assume the shared fleet key. Planned Messenger + JSON ops " +
            "mesh.capabilities.get / mesh.status / mesh.up / mesh.down / mesh.ip.get. External control " +
            "default OFF. Node-id prefix ^mesh-[0-9a-f]{32}$. Controller-side DEBUG + Mesh-own RELEASE " +
            "certs are catalogued (KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256 / " +
            "VICTORIOUS_MESH_RELEASE_SHA256, enforceable).",
        androidPackages = listOf("com.victorious.mesh"),
        expectedSigningCertSha256 = listOf(
            KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
            KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
        ),
    )

    val BROWSER = FleetMemberDescriptor(
        id = "browser",
        displayName = "Victorious Browser",
        repo = null,
        packageOrBinary = "com.victorious.browser",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.browser.node"),
        providerId = "browser",
        existingToolNames = listOf("browser_status"),
        catalogStubName = "browser_status",
        wiringOrder = 9,
        transports = listOf(
            "messenger+json envelope (planned: action com.victorious.browser.action.BRIDGE; " +
                "Message what=1 request, reply what=2 response; " +
                "caller identity = Message.sendingUid, not Binder.getCallingUid; " +
                "node-id prefix ^browser-[0-9a-f]{32}$)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but Browser is not " +
            "paired and the catalog stub still does not bind. Pair the node to call it. External " +
            "control default OFF. Never DependencyGuard, never a shared signing key. Browser keeps its own " +
            "release key (CI-only; not the shared fleet key). Release cert is [planned/unverified].",
        refusalMessage = "Victorious Browser is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Planned action com.victorious.browser.action.BRIDGE (Message what=1 request / what=2 response). " +
            "Documented ops browser.capabilities.get / browser.open_url{url} / browser.panel.list / " +
            "browser.panel.open{panel_id} / browser.recon.summary{limit?,since?} / " +
            "browser.proxy.set{enabled} (fire-and-forget) + pollable browser.proxy.status. " +
            "Error categories invalid_parameters / not_paired / not_found / proxy_error / internal. " +
            "External control default OFF; caller identity = Message.sendingUid (not Binder.getCallingUid). " +
            "Node-id prefix ^browser-[0-9a-f]{32}$. Browser keeps its own release key (CI-only; not the " +
            "shared fleet key). Debug SHA-256 2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:" +
            "37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1. Release cert is [planned/unverified]. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge, File Manager, Keyboard, and Mesh. Never DependencyGuard, never " +
            "shared signing key, never KnownCompanions, never BIND_* signature permission, never AIDL. " +
            "Package com.victorious.browser. Browser keeps its own release key (CI-only; not the shared " +
            "fleet key); release cert is [planned/unverified]. Planned Messenger + JSON ops " +
            "browser.capabilities.get / browser.open_url / browser.panel.list / browser.panel.open / " +
            "browser.recon.summary / browser.proxy.set / browser.proxy.status. External control " +
            "default OFF. Caller identity = Message.sendingUid (not Binder.getCallingUid). " +
            "Node-id prefix ^browser-[0-9a-f]{32}$. Controller-side DEBUG cert is catalogued " +
            "(KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256, enforceable).",
        androidPackages = listOf("com.victorious.browser"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256),
    )

    /**
     * VictorSMS SMS remote-command source **and** `remote.command` target
     * registry / pairing-table row (alongside [VICTOR_SPOOF]).
     *
     * Status: **[planned/pending-merge]** — scaffold PR still open.
     * Debug SHA-256 is documented
     * ([KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED], alias
     * `androiddebugkey`) but **[expectedSigningCertSha256] stays empty** so
     * the catalogue is fail-open / unpinned. Do **not** enforce the pin yet.
     * Release cert TBD — do not invent fingerprints.
     *
     * Isolated from privileged/destructive paths: the catalogued inbound op
     * `sms.remote_command.forward` is never wipe/power-off/lock/flash/
     * bootloader. A forwarded commandId is classified independently.
     * VictorSMS owns whitelist + code word; DCA owns commandId→action.
     *
     * TODO: enforce the debug pin after the scaffold PR merges; pin release
     * later.
     */
    val VICTOR_SMS = FleetMemberDescriptor(
        id = "victorsms",
        displayName = "VictorSMS",
        repo = null,
        packageOrBinary = "com.victorious.sms (debug==release; no applicationIdSuffix)",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.victorsms.node"),
        providerId = "victorsms",
        existingToolNames = listOf("victorsms_status"),
        catalogStubName = "victorsms_status",
        wiringOrder = 10,
        transports = listOf(
            "messenger+json envelope (confirmed: action com.victorious.sms.action.BRIDGE; " +
                "Message what=1 request; protocol_major 1; " +
                "inbound op sms.remote_command.forward; controller op remote.command; " +
                "caller identity = Message.sendingUid, not Binder.getCallingUid; " +
                "node-id prefix ^victorsms-[0-9a-f]{32}$)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but VictorSMS is not " +
            "paired and inbound sms.remote_command.forward is not live-wired. Pair the node to call it. " +
            "External control default OFF + explicit consent on their side. SMS whitelist and code " +
            "word live in VictorSMS, not here. Debug cert is documented " +
            "(KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED, alias androiddebugkey) " +
            "but [planned/pending-merge] unenforced / fail-open — expectedSigningCertSha256 stays empty. " +
            "TODO: enforce after scaffold merge; pin release SHA-256 once published. " +
            "Never DependencyGuard, never a shared signing key.",
        refusalMessage = "VictorSMS is PLANNED EXTERNAL_NODE [planned/pending-merge] — this stub does not " +
            "bind Messenger. Confirmed action com.victorious.sms.action.BRIDGE (Message what=1 request, " +
            "protocol_major 1). Inbound op sms.remote_command.forward. Node-id prefix " +
            "^victorsms-[0-9a-f]{32}$. debug==release package com.victorious.sms. Debug SHA-256 " +
            "BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A " +
            "(alias androiddebugkey) is documented, not enforced. Catalogue is fail-open/unpinned. " +
            "Release cert TBD. Isolated from wipe/power-off/lock/flash/bootloader. External control " +
            "default OFF + explicit consent. SMS whitelist and code word live in VictorSMS; DCA owns " +
            "commandId→action. trigger{} is informational. Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge / File Manager / Keyboard / Mesh / Browser / VictorSpoof. Never " +
            "DependencyGuard, never shared signing key, never KnownCompanions, never BIND_* " +
            "signature permission, never AIDL. Package com.victorious.sms (debug==release). " +
            "remote.command target registry + pairing-table row alongside VictorSpoof. " +
            "Inbound op sms.remote_command.forward. Isolated from privileged/destructive paths. " +
            "SMS gating (whitelist + code word) lives in VictorSMS; DCA owns commandId→action. " +
            "trigger{} is informational, never authorization. External control default OFF. " +
            "Caller identity = Message.sendingUid. Node-id prefix ^victorsms-[0-9a-f]{32}$. " +
            "[planned/pending-merge]. Debug SHA-256 " +
            "BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A " +
            "(alias androiddebugkey) is documented, not enforced — expectedSigningCertSha256 is empty " +
            "(fail-open). Release cert TBD. TODO: enforce debug pin after scaffold merge; pin release later.",
        androidPackages = listOf("com.victorious.sms"),
        expectedSigningCertSha256 = emptyList(),
    )

    /**
     * VictorSpoof novelty/prank caller-ID node. Signing certs are **not yet
     * committed** — [expectedSigningCertSha256] stays empty so the catalogue
     * is fail-open / unpinned.
     *
     * Isolated from privileged/destructive paths: mapped actions stay
     * non-destructive unless Don explicitly maps otherwise.
     *
     * TODO: pin debug + release SHA-256 once VictorSpoof publishes them.
     * Do not invent fingerprints.
     */
    val VICTOR_SPOOF = FleetMemberDescriptor(
        id = "victorspoof",
        displayName = "VictorSpoof",
        repo = null,
        packageOrBinary = "com.victorious.spoof (novelty/prank caller-ID; EXTERNAL_NODE)",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.victorspoof.node"),
        providerId = "victorspoof",
        existingToolNames = listOf("victorspoof_status"),
        catalogStubName = "victorspoof_status",
        wiringOrder = 11,
        transports = listOf(
            "messenger+json envelope (planned: action com.victorious.spoof.action.BRIDGE; " +
                "Message what=1 request; protocol_major 1; " +
                "caller identity = Message.sendingUid, not Binder.getCallingUid; " +
                "remote.command target; isolated from wipe/power-off/lock/flash/bootloader)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.REVERSIBLE,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but VictorSpoof is not " +
            "paired. Pair the node to call it. Isolated from privileged/destructive paths. " +
            "TODO: pin debug+release SHA-256 (currently unpinned / fail-open). " +
            "Never DependencyGuard, never a shared signing key.",
        refusalMessage = "VictorSpoof is PLANNED EXTERNAL_NODE — this stub does not bind Messenger. " +
            "Planned action com.victorious.spoof.action.BRIDGE (Message what=1 request, protocol_major 1). " +
            "Package com.victorious.spoof. Signing certs are [CONTRACT TBD] — catalogue " +
            "is fail-open/unpinned. TODO: pin debug+release SHA-256. Isolated from " +
            "wipe/power-off/lock/flash/bootloader. Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge / File Manager / Keyboard / Mesh / Browser / VictorSMS. Never " +
            "DependencyGuard, never shared signing key, never KnownCompanions, never BIND_* " +
            "signature permission, never AIDL. Package com.victorious.spoof. Novelty/prank " +
            "caller-ID — isolated from privileged/destructive paths unless Don explicitly maps " +
            "otherwise. Caller identity = Message.sendingUid. " +
            "TODO: pin debug+release SHA-256 — expectedSigningCertSha256 is empty (fail-open).",
        androidPackages = listOf("com.victorious.spoof"),
        expectedSigningCertSha256 = emptyList(),
    )

    /**
     * Victorious Flash Android EXTERNAL_NODE (phone-side Messenger). Distinct
     * from the Linux SSH `core-flash` node (`vf-` prefix,
     * `docs/FLASH_INTEGRATION.md`).
     *
     * Debug SHA-256 is **enforced** (flash PR #18 merged to main; debug cert
     * is stable). Release cert TBD — do not invent.
     *
     * `gadget.expose` is DESTRUCTIVE: always confirmation_required plus an
     * on-phone ERASE confirmation. Isolated from other destructive paths the
     * same way wipe/power-off/lock/bootloader are gated. This catalog stub
     * does not bind Messenger and never exposes a gadget.
     */
    val VICTORIOUS_FLASH = FleetMemberDescriptor(
        id = "victorious-flash",
        displayName = "Victorious Flash",
        repo = "Victorious93/victorious-flash",
        packageOrBinary = "com.victorious.flash.android",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.victorious-flash.node"),
        providerId = "victorious-flash",
        existingToolNames = listOf("victorious_flash_status"),
        catalogStubName = "victorious_flash_status",
        wiringOrder = 12,
        transports = listOf(
            "messenger+json envelope (confirmed: exported service, action " +
                "com.victorious.flash.android.FLEET_BRIDGE; " +
                "Message what=1 json envelope + replyTo, reply what=2; " +
                "node-id prefix ^vfa-[0-9a-f]{32}$; " +
                "ops status.get / capabilities.get (read); " +
                "netboot.serve.start / netboot.serve.stop (action); " +
                "otg.write{deviceName}; gadget.expose (DESTRUCTIVE, always " +
                "confirmation_required + on-phone ERASE confirmation))",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.DESTRUCTIVE,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but " +
            "Victorious Flash is not paired and this stub does not bind. Pair the node " +
            "to call read ops. gadget.expose is DESTRUCTIVE — always confirmation_required " +
            "plus on-phone ERASE confirmation; isolated from other destructive gating. " +
            "Debug cert is enforced (KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256, " +
            "flash PR #18 merged). Release cert TBD — do not invent. Never DependencyGuard, " +
            "never a shared signing key. Distinct from the Linux SSH core-flash node (vf-).",
        refusalMessage = "Victorious Flash is PLANNED EXTERNAL_NODE — this stub does not bind " +
            "Messenger. Confirmed action com.victorious.flash.android.FLEET_BRIDGE " +
            "(exported service; Message what=1 json envelope + replyTo, reply what=2). " +
            "Package com.victorious.flash.android. Node-id prefix ^vfa-[0-9a-f]{32}$. " +
            "Read ops status.get / capabilities.get. Action ops netboot.serve.start / " +
            "netboot.serve.stop / otg.write{deviceName}. gadget.expose is DESTRUCTIVE " +
            "(always confirmation_required + on-phone ERASE confirmation). Debug SHA-256 " +
            "2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:" +
            "86:38:1C:6A:F6:ED is catalogued and enforced. Release cert TBD. Distinct from " +
            "the Linux SSH core-flash node (vf- prefix). Never DependencyGuard, never AIDL. " +
            "Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge / File Manager / Keyboard / Mesh / Browser / " +
            "VictorSMS / VictorSpoof. Never DependencyGuard, never shared signing key, never " +
            "KnownCompanions, never BIND_* signature permission, never AIDL. Package " +
            "com.victorious.flash.android. Messenger action " +
            "com.victorious.flash.android.FLEET_BRIDGE. Node-id prefix ^vfa-[0-9a-f]{32}$. " +
            "Read ops status.get / capabilities.get; action ops netboot.serve.start / " +
            "netboot.serve.stop / otg.write{deviceName}. gadget.expose is DESTRUCTIVE and " +
            "always confirmation_required + on-phone ERASE confirmation — isolated like " +
            "other destructive gating. Controller-side DEBUG cert is catalogued and " +
            "enforced (KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256; flash PR #18 " +
            "merged, CI green 7a4c32c). Release cert TBD — do not invent. Distinct from " +
            "the Linux SSH core-flash node (vf- prefix, docs/FLASH_INTEGRATION.md).",
        androidPackages = listOf("com.victorious.flash.android"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
    )

    /**
     * VictorLaunch status-tray consumer **and** Messenger bridge node.
     *
     * Debug SHA-256 is **enforced**
     * ([KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256]) after
     * victorlaunch PR #6 merged and the debug key rotated (CI secrets
     * only — never committed). The previous fail-open pin
     * `1B:06:A8:9E:…:BA:CA` is revoked. Release cert TBD — do not
     * invent fingerprints.
     *
     * Implements status-tray consumer, `config.export`/`config.import`,
     * envelope v1.1 dispatcher with ACTION OPS OFF BY DEFAULT (external
     * control default OFF), plus MANUAL + in-app Help.
     */
    val VICTOR_LAUNCH = FleetMemberDescriptor(
        id = "victorlaunch",
        displayName = "VictorLaunch",
        repo = null,
        packageOrBinary = "com.victorious.launch",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        status = FleetHonestyStatus.PLANNED,
        capabilityId = CapabilityId("fleet.victorlaunch.node"),
        providerId = "victorlaunch",
        existingToolNames = listOf("victorlaunch_status"),
        catalogStubName = "victorlaunch_status",
        wiringOrder = 13,
        transports = listOf(
            "messenger+json envelope (planned: action com.victorious.launch.action.BRIDGE; " +
                "Message what=1 request / what=2 reply; protocol_major 1; " +
                "caller identity = Message.sendingUid, not Binder.getCallingUid; " +
                "node-id prefix ^victorlaunch-[0-9a-f]{32}$; " +
                "status-tray consumer + bridge node; config.export/import; " +
                "envelope v1.1 dispatcher; ACTION OPS OFF BY DEFAULT)",
        ),
        usesAidl = false,
        usesDependencyGuard = false,
        riskTier = RiskTier.READ_ONLY,
        nextStep = "Generic Messenger client exists (core-fleet-messenger + :app) but VictorLaunch is not " +
            "paired and this stub does not bind. Pair the node to call it. External control " +
            "default OFF / ACTION OPS OFF BY DEFAULT. Debug cert is enforced " +
            "(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256; victorlaunch PR #6 merged; " +
            "rotated key in CI secrets only). Previous 1B:06:A8:…:BA:CA pin is revoked. " +
            "Release cert TBD — do not invent. Never DependencyGuard, never a shared signing key.",
        refusalMessage = "VictorLaunch is PLANNED EXTERNAL_NODE — this stub does not " +
            "bind Messenger. Planned action com.victorious.launch.action.BRIDGE (Message what=1 request / " +
            "what=2 reply, protocol_major 1). Package com.victorious.launch. Node-id prefix " +
            "^victorlaunch-[0-9a-f]{32}$. Status-tray consumer + bridge node. config.export/import " +
            "supported. Envelope v1.1 dispatcher; ACTION OPS OFF BY DEFAULT (external control default OFF). " +
            "MANUAL + in-app Help. Debug SHA-256 " +
            "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69 " +
            "is catalogued and enforced. Previous 1B:06:A8:…:BA:CA pin is revoked. Release cert TBD. " +
            "Source victorlaunch PR #6 merged (rotated key in CI secrets only). Never DependencyGuard, " +
            "never AIDL. Capability state is REQUIRES_EXTERNAL_SERVICE.",
        notes = "EXTERNAL_NODE like RootForge / File Manager / Keyboard / Mesh / Browser / VictorSMS / " +
            "VictorSpoof / Flash. Never DependencyGuard, never shared signing key, never " +
            "KnownCompanions, never BIND_* signature permission, never AIDL. Package " +
            "com.victorious.launch. status-tray consumer AND bridge node. config.export/import " +
            "supported. Envelope v1.1 dispatcher; ACTION OPS OFF BY DEFAULT (external control " +
            "default OFF). MANUAL + in-app Help. Caller identity = Message.sendingUid. " +
            "Node-id prefix ^victorlaunch-[0-9a-f]{32}$. Debug SHA-256 " +
            "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69 " +
            "(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256) is catalogued and enforced " +
            "(victorlaunch PR #6 merged; rotated key in CI secrets only). Previous " +
            "1B:06:A8:…:BA:CA pin is revoked. Release cert TBD — do not invent.",
        androidPackages = listOf("com.victorious.launch"),
        expectedSigningCertSha256 = listOf(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256),
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
        BROWSER,
        VICTOR_SMS,
        VICTOR_SPOOF,
        VICTORIOUS_FLASH,
        VICTOR_LAUNCH,
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
        appendLine("DroidCommand AI room fleet — 13 members. None is LIVE.")
        appendLine("Wiring order: Pentest (when repo exists) → RootForge Messenger → VictorSuite RUN_COMMAND → EDL companion → KYC last → File Manager (when their identity/default-off PR lands) → Keyboard (catalog only; direction open) → Mesh (catalog only; own signing keys) → Browser (catalog only; own signing keys) → VictorSMS (EXTERNAL_NODE, SMS remote-command source; debug cert documented [planned/pending-merge] unenforced; release TBD) → VictorSpoof (EXTERNAL_NODE, novelty/prank caller-ID; isolated from destructive; certs TBD) → Flash (EXTERNAL_NODE, Messenger FLEET_BRIDGE; gadget.expose destructive always confirmation_required + on-phone ERASE; debug cert enforced; release TBD) → VictorLaunch (EXTERNAL_NODE, status-tray consumer + bridge node; config.export/import; envelope v1.1; ACTION OPS OFF BY DEFAULT; debug cert enforced; release TBD). KYC companion APK is a remote.command EXTERNAL_NODE [provisional/pending-stable-cert] (fail-open; remote.command OFF-by-default; all PII ops remoteAllowed:false).")
        appendLine("AIDL + DependencyGuard apply only to BOUND_COMPANION (Pentest, EDL). RootForge/File Manager/Keyboard/Mesh/Browser/VictorSMS/VictorSpoof/Flash/VictorLaunch/KYC companion Messenger is not that.")
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
        appendLine("HackerAI (ai.hackerai.companion) is a BOUND_COMPANION already in KnownCompanions; not one of these thirteen.")
        appendLine("Controller-side node signature check: NodeSignatureVerifier (core-security). Default missing-expected policy is ALLOW.")
        appendLine("Catalogue is partial — fail-closed should be enabled only once remaining members and verified release pins are catalogued.")
        appendLine("Enforceable: DCA debug; Mesh debug+own release (com.victorious.mesh); Browser debug (com.victorious.browser); Flash debug (com.victorious.flash.android); VictorLaunch debug (com.victorious.launch); Pentest debug; EDL debug; HackerAI debug+verified release 9E:A7; RootForge debug (ai.rootforge.os.debug); Keyboard debug (not .bench); VictorSuite debug + own release.")
        appendLine("HackerAI is the only member whose shared-fleet RELEASE 9E:A7:… is firsthand verified and enforced. DCA/Pentest/EDL release stays [planned/unverified]. Browser RELEASE is own-key [planned/unverified] (CI-only, cert TBD). Flash RELEASE is TBD. VictorLaunch RELEASE is TBD.")
        appendLine("File-Manager DEBUG is [unstable/CI-secrets] — documented, not enforced. VictorSMS DEBUG is [planned/pending-merge] — documented, not enforced. VictorLaunch DEBUG is enforced (node PR #6 merged; rotated key in CI secrets only; previous 1B:06:A8:…:BA:CA pin revoked). KYC-AuditLab companion DEBUG is [provisional/pending-stable-cert] — active fail-open pin is per-machine debug.keystore 6F:46:…; pending permanent 22:4F:… (KYC PR #29, unmerged) recorded not enforced. Do not enforce either. Release cert TBD. BakerPi is not catalogued.")
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
