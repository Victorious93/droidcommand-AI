package ai.droidcommand.app.fleet.install

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.fleet.update.FleetAppSource

/**
 * Which GitHub repo each fleet app's APKs are published from. Only apps with a row here get a
 * "Download & install" button; HackerAI and Termux have none (no verified release source), so their cards keep
 * saying "get it from its official source".
 *
 * The repo names come from the owner's own repository list. Whether a repo actually attaches an `.apk` to a
 * release is not verified here: when it does not, the button reports "nothing published" instead of guessing.
 */
object FleetSources {
    private const val OWNER = "Victorious93"

    val all: List<FleetAppSource> = listOf(
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
    )

    fun forId(fleetId: String): FleetAppSource? = all.firstOrNull { it.fleetId == fleetId }
}
