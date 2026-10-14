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
 * Only HackerAI and Pentest-Swarm belong here. RootForge, VictorSuite,
 * KYC-AuditLab, and EDL-Commander are different integration classes — see
 * `ai.droidcommand.security.KnownFleet` and `docs/FLEET_CONTROL.md`. Do not
 * add those members to this object; that would imply AIDL + DependencyGuard.
 */
object KnownCompanions {
    const val HACKERAI_PACKAGE = "ai.hackerai.companion"
    const val PENTESTSWARM_PACKAGE = "ai.pentestswarm.companion"

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
}
