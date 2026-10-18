package ai.droidcommand.companion

/**
 * Static description of a companion APK known to DroidCommand AI.
 *
 * @param id           Stable identifier matching [ICompanionService.getCompanionId].
 * @param packageName  Android package name (e.g. "ai.hackerai.companion").
 *                     Used by [CompanionRegistry] when calling PackageManager and
 *                     constructing the bind Intent.
 * @param version      Version string reported by [ICompanionService.getCompanionVersion].
 * @param capabilities Capability IDs this companion provides; subset of what
 *                     [ICompanionService.listCapabilities] returns at runtime.
 *                     Pre-populated so DCA can register capabilities before binding.
 */
data class CompanionDescriptor(
    val id: String,
    val packageName: String,
    val version: String,
    val capabilities: List<String>,
)

/**
 * Well-known **bound companion APK** descriptors.
 *
 * HackerAI and Pentest-Swarm have AIDL + APK source. EDL-Commander is the
 * same class (AIDL + same-signing + DependencyGuard) but the APK/AIDL are
 * still PLANNED — do not bind it from [CompanionRegistry] until
 * `IEDLCommanderService` exists. RootForge, File Manager, Keyboard, VictorSuite,
 * and KYC-AuditLab are different classes — see `ai.droidcommand.security.KnownFleet`
 * and `docs/FLEET_CONTROL.md`. Do not add those here.
 */
object KnownCompanions {
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"
    const val PENTESTSWARM_PACKAGE = "ai.pentestswarm.companion"

    /** Planned package id. The APK does not exist; do not bind this. */
    const val EDLCOMMANDER_PACKAGE = "ai.edlcommander.companion"

    val HACKERAI = CompanionDescriptor(
        id = "hackerai",
        packageName = HACKERAI_PACKAGE,
        version = "0.1.0",
        capabilities = listOf(
            "ai.companion.hackerai.agent_task",
            "ai.companion.hackerai.validate_finding",
            "ai.companion.hackerai.skill_catalog",
        ),
    )

    val PENTESTSWARM = CompanionDescriptor(
        id = "pentestswarm",
        packageName = PENTESTSWARM_PACKAGE,
        version = "0.1.0",
        capabilities = listOf(
            "ai.companion.pentestswarm.campaign",
            "ai.companion.pentestswarm.run_chain",
            "ai.companion.pentestswarm.run_playbook",
        ),
    )

    /**
     * Planned slot only. Version `0.0.0-planned` means there is no APK and
     * no AIDL yet. [CompanionRegistry] must not bind this package.
     */
    val EDLCOMMANDER = CompanionDescriptor(
        id = "edlcommander",
        packageName = EDLCOMMANDER_PACKAGE,
        version = "0.0.0-planned",
        capabilities = listOf("ai.companion.edlcommander.status"),
    )
}
