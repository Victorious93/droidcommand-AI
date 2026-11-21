package ai.droidcommand.app.fleet.install

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetExtraApps
import ai.droidcommand.fleet.update.FleetAppSource

/**
 * Which GitHub repo each fleet app's APKs are published from. Only apps with a row here get a
 * "Download & install" button; Termux and VictorTrade have none (no verified public release source), so
 * they keep saying "get it from its official source".
 *
 * The repo names come from the owner's own repository list. Whether a repo actually attaches an `.apk` to a
 * release is not verified here: when it does not, the button reports "nothing published" instead of guessing.
 */
object FleetSources {
    private const val OWNER = "Victorious93"

    val all: List<FleetAppSource> = listOf(
        // HackerAI's applicationId (ai.hackerai.companion) was read from its repo's Android build. That repo's release workflow
        // creates a DRAFT release, which is never offered, so this reads "Not published" until the owner publishes one. HackerAI
        // is a same-signing bound companion: an APK signed with a different key installs but its bind is refused by Android.
        FleetAppSource("hackerai", "HackerAI", FleetCatalog.HACKERAI_PACKAGE, "$OWNER/HackerAI-Commander"),
        FleetAppSource("pentestswarm", "Pentest-Swarm", FleetCatalog.PENTESTSWARM_PACKAGE, "$OWNER/Pentest-Swarm-AI"),
        FleetAppSource("rootforge", "RootForge OS", FleetCatalog.ROOTFORGE_PACKAGE, "$OWNER/rootforge-os"),
        FleetAppSource("edl-commander", "EDL-Commander", FleetCatalog.EDLCOMMANDER_PACKAGE, "$OWNER/EDL-Commander"),
        FleetAppSource("kyc-auditlab", "KYC-AuditLab", FleetCatalog.KYC_PACKAGE, "$OWNER/KYC-AuditLab"),
        FleetAppSource("file-manager", "Victorious File Manager", FleetCatalog.FILEMANAGER_PACKAGE, "$OWNER/File-Manager"),
        FleetAppSource("keyboard", "Victorious Keyboard", FleetCatalog.KEYBOARD_PACKAGE, "$OWNER/Victorious-Keyboard"),
        FleetAppSource("mesh", "Victorious Mesh", FleetCatalog.MESH_PACKAGE, "$OWNER/victorious-mesh"),
        FleetAppSource("browser", "Victorious Browser", FleetCatalog.BROWSER_PACKAGE, "$OWNER/victorious-browser"),
        FleetAppSource("victorsms", "VictorSMS", FleetCatalog.VICTORSMS_PACKAGE, "$OWNER/victorsms"),
        FleetAppSource("victorspoof", "VictorSpoof", FleetCatalog.VICTORSPOOF_PACKAGE, "$OWNER/victorspoof"),
        FleetAppSource("victorious-flash", "Victorious Flash", FleetCatalog.FLASH_PACKAGE, "$OWNER/victorious-flash"),
        FleetAppSource("victorlaunch", "VictorLaunch", FleetCatalog.VICTORLAUNCH_PACKAGE, "$OWNER/victorlaunch"),
        // Repo names below were read from the owner's repository list. VictorTrade is not here: its repo is private, so an
        // unauthenticated download cannot work. None of ShizukuV / VictorBots / rustdeskV had a published GitHub release when
        // this was written (checked 2026-11-21), so their buttons report "no APK published yet" until one is attached.
        FleetAppSource("victorbots", "VictorBots", FleetExtraApps.VICTORBOTS_PACKAGE, "$OWNER/VictorBots"),
        FleetAppSource("shizukuv", "ShizukuV", FleetExtraApps.SHIZUKUV_PACKAGE, "$OWNER/ShizukuV"),
        FleetAppSource("rustdeskv", "rustdeskV", FleetExtraApps.RUSTDESKV_PACKAGE, "$OWNER/rustdeskV"),
        FleetAppSource("victorcam", "VictorCam", FleetExtraApps.VICTORCAM_PACKAGE, "$OWNER/victorcam"),
        FleetAppSource("victortaskbar", "VictorTaskbar", FleetExtraApps.VICTORTASKBAR_PACKAGE, "$OWNER/victortaskbar"),
        FleetAppSource("victormango", "VictorMango", FleetExtraApps.VICTORMANGO_PACKAGE, "$OWNER/victormango"),
        FleetAppSource("victorgps", "VictorGPS", FleetExtraApps.VICTORGPS_PACKAGE, "$OWNER/victorgps"),
    )

    fun forId(fleetId: String): FleetAppSource? = all.firstOrNull { it.fleetId == fleetId }

    /** Repos for the "Open repo" button of apps that have no download source. Termux / VictorSuite's source is the owner's VictorSuite repo. */
    private val repoOnly: Map<String, String> = mapOf(
        "termux" to "$OWNER/VictorSuite",
        "victortrade" to "$OWNER/victortrade",
    )

    /** The `owner/name` repo to open for [fleetId], whether or not it has downloadable releases. Null when none is known. */
    fun repoFor(fleetId: String): String? = forId(fleetId)?.repo ?: repoOnly[fleetId]
}
