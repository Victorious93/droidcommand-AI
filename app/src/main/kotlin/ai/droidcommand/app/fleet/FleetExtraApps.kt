package ai.droidcommand.app.fleet

import ai.droidcommand.remotepc.RustDeskContract

/** How an app in [FleetExtraApps] relates to this hub. Neither value is a control-plane node. */
enum class FleetExtraLink {
    /**
     * Calls this app's `COMPANION_ASSIST` entry point (`core-security` `InboundEntryPointCatalog`). The owner links the
     * installed copy with Settings → Password pairing (package + observed signing cert, trust on first use).
     */
    PASSWORD_LINK,

    /** Installable and detectable, but DroidCommand has no bridge to it. There is nothing to pair. */
    NO_HUB_LINK,
}

/**
 * An app of the owner's that belongs on the Fleet screen's install / download list but is deliberately NOT a
 * [FleetCatalog] member.
 *
 * Why separate: [FleetNodes] maps every [FleetCatalog] member into the Fleet Orchestrator's control plane. These apps
 * have no Messenger bridge and no AIDL contract, so putting them there would claim a control path that does not exist.
 * This list only drives what the screen shows (icon, install state, download, how to link). It never binds, never sends a
 * command and never changes a pairing.
 */
data class FleetExtraApp(
    val id: String,
    val name: String,
    val packageName: String,
    val role: String,
    val link: FleetExtraLink,
    val about: String,
)

object FleetExtraApps {
    const val VICTORBOTS_PACKAGE = "com.victorious.bots"
    const val VICTORCAM_PACKAGE = "com.victorious.cam"
    const val VICTORTRADE_PACKAGE = "com.victorious.trade"
    const val VICTORTASKBAR_PACKAGE = "com.victorious.taskbar"
    const val VICTORMANGO_PACKAGE = "com.victorious.mango"
    const val VICTORGPS_PACKAGE = "com.victorious.gps"

    /** applicationId of the `Victorious93/ShizukuV` manager (`BridgeIdentity.APPLICATION_ID` in that repo). */
    const val SHIZUKUV_PACKAGE = "com.victorious.shizukuv"

    /** The Victorious RustDesk fork keeps RustDesk's applicationId. Same constant `core-remote-pc` already uses. */
    const val RUSTDESKV_PACKAGE = RustDeskContract.PACKAGE

    private const val INBOUND_ABOUT =
        "DroidCommand can receive an assist request from this app. Nothing runs until you link the installed copy " +
            "(Settings → Password pairing) and approve each request. Remote control stays off."

    val VICTORBOTS = FleetExtraApp(
        id = "victorbots",
        name = "VictorBots",
        packageName = VICTORBOTS_PACKAGE,
        role = "Specialised AI bots workspace",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorBots is your workspace for specialised AI bots. It works on its own; it can also send text-assist requests to DroidCommand. " + INBOUND_ABOUT,
    )

    val VICTORCAM = FleetExtraApp(
        id = "victorcam",
        name = "VictorCam",
        packageName = VICTORCAM_PACKAGE,
        role = "Another Victorious app",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorCam can send assist requests to DroidCommand. $INBOUND_ABOUT",
    )

    val VICTORTRADE = FleetExtraApp(
        id = "victortrade",
        name = "VictorTrade",
        packageName = VICTORTRADE_PACKAGE,
        role = "Another Victorious app",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorTrade can send assist requests to DroidCommand. $INBOUND_ABOUT",
    )

    val VICTORTASKBAR = FleetExtraApp(
        id = "victortaskbar",
        name = "VictorTaskbar",
        packageName = VICTORTASKBAR_PACKAGE,
        role = "Another Victorious app",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorTaskbar can send assist requests to DroidCommand. $INBOUND_ABOUT",
    )

    val VICTORMANGO = FleetExtraApp(
        id = "victormango",
        name = "VictorMango",
        packageName = VICTORMANGO_PACKAGE,
        role = "Another Victorious app",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorMango can send assist requests to DroidCommand. $INBOUND_ABOUT",
    )

    val VICTORGPS = FleetExtraApp(
        id = "victorgps",
        name = "VictorGPS",
        packageName = VICTORGPS_PACKAGE,
        role = "Another Victorious app",
        link = FleetExtraLink.PASSWORD_LINK,
        about = "VictorGPS can send assist requests to DroidCommand. $INBOUND_ABOUT",
    )

    val SHIZUKUV = FleetExtraApp(
        id = "shizukuv",
        name = "ShizukuV",
        packageName = SHIZUKUV_PACKAGE,
        role = "Local privileged-access manager",
        link = FleetExtraLink.NO_HUB_LINK,
        about = "ShizukuV is your own build of the Shizuku privileged-access service, with its own identity. " +
            "DroidCommand's Shizuku support currently talks to the standard Shizuku manager " +
            "(moe.shizuku.privileged.api), not to ShizukuV, so there is nothing to pair here yet.",
    )

    val RUSTDESKV = FleetExtraApp(
        id = "rustdeskv",
        name = "rustdeskV",
        packageName = RUSTDESKV_PACKAGE,
        role = "Remote desktop (your RustDesk build)",
        link = FleetExtraLink.NO_HUB_LINK,
        about = "rustdeskV is your RustDesk build. DroidCommand's My PC tile only hands a peer id to this installed " +
            "app (rustdesk://<id>); the session, keys and passwords stay inside rustdeskV. There is nothing to pair here.",
    )

    val all: List<FleetExtraApp> = listOf(
        VICTORBOTS,
        VICTORCAM,
        VICTORTRADE,
        VICTORTASKBAR,
        VICTORMANGO,
        VICTORGPS,
        SHIZUKUV,
        RUSTDESKV,
    )

    val packages: Set<String> get() = all.map { it.packageName }.toSet()

    fun forId(id: String): FleetExtraApp? = all.firstOrNull { it.id == id }
}

/** An extra app plus whether this phone has it. */
data class FleetExtraCard(val app: FleetExtraApp, val installed: Boolean)
