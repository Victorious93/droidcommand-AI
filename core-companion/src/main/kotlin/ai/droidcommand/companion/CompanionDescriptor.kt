package ai.droidcommand.companion

import ai.droidcommand.security.EdlCompanionIds

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
 * same class (AIDL + same-signing + DependencyGuard). Its AIDL lives in
 * [EdlCompanionIds.AIDL_PACKAGE] (`IEdlCompanion`), copied from EDL main.
 * The Fleet orchestrator still does not auto-bind it (never device-verified).
 * RootForge, File Manager, Keyboard, VictorSuite, and KYC-AuditLab are
 * different classes — see `ai.droidcommand.security.KnownFleet` and
 * `docs/FLEET_CONTROL.md`. Do not add those here.
 */
object KnownCompanions {
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"
    const val PENTESTSWARM_PACKAGE = "ai.pentestswarm.companion"

    /** applicationId on EDL main after PR #8. */
    const val EDLCOMMANDER_PACKAGE = EdlCompanionIds.PACKAGE
    const val EDLCOMMANDER_BIND_ACTION = EdlCompanionIds.BIND_ACTION
    const val EDLCOMMANDER_BIND_PERMISSION = EdlCompanionIds.BIND_PERMISSION

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
     * Planned slot. AIDL is copied; the Fleet orchestrator does not auto-bind
     * this package. Version `0.0.0-planned` means this repo has never
     * device-verified a live bind.
     */
    val EDLCOMMANDER = CompanionDescriptor(
        id = "edlcommander",
        packageName = EDLCOMMANDER_PACKAGE,
        version = "0.0.0-planned",
        capabilities = listOf("ai.companion.edlcommander.status"),
    )
}
