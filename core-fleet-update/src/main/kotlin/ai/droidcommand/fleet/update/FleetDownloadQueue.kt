package ai.droidcommand.fleet.update

/** What the "Download your apps" list should offer for one app. */
enum class FleetDownloadAction {
    /** Not on the phone and a release APK exists. */
    DOWNLOAD,

    /** On the phone, and a newer release APK exists. */
    UPDATE,

    /** On the phone and nothing newer is published. */
    UP_TO_DATE,

    /** No release APK to fetch (no release yet, or the release has no APK for this phone). */
    NOT_PUBLISHED,

    /** This app has no release source configured at all. */
    NO_SOURCE,

    /** Installed, but the version cannot be compared with the release tag, so nothing is claimed. */
    UNKNOWN,
}

/** One line of the download list: the app, what the planner decided (null when there is no source) and its state. */
data class FleetDownloadRow(
    val fleetId: String,
    val displayName: String,
    val source: FleetAppSource?,
    val decision: UpdateDecision?,
) {
    val action: FleetDownloadAction
        get() = when {
            source == null || decision == null -> FleetDownloadAction.NO_SOURCE
            decision is UpdateDecision.Install -> FleetDownloadAction.DOWNLOAD
            decision is UpdateDecision.Update -> FleetDownloadAction.UPDATE
            decision is UpdateDecision.UpToDate -> FleetDownloadAction.UP_TO_DATE
            decision is UpdateDecision.NothingPublished -> FleetDownloadAction.NOT_PUBLISHED
            else -> FleetDownloadAction.UNKNOWN
        }

    /** True when a tap should download and hand an APK to Android's installer. */
    val canFetch: Boolean get() = action == FleetDownloadAction.DOWNLOAD || action == FleetDownloadAction.UPDATE
}

/**
 * Pure rules behind the "Download all" button. It selects only rows that have a real release APK to fetch, never
 * guesses, and never selects an app twice. It does not touch the network or the phone.
 */
object FleetDownloadQueue {
    /** The apps "Download all" would fetch, in list order. [includeUpdates] false means missing apps only. */
    fun queue(rows: List<FleetDownloadRow>, includeUpdates: Boolean = true): List<FleetAppSource> =
        rows.asSequence()
            .filter { row ->
                when (row.action) {
                    FleetDownloadAction.DOWNLOAD -> true
                    FleetDownloadAction.UPDATE -> includeUpdates
                    else -> false
                }
            }
            .mapNotNull { it.source }
            .distinctBy { it.fleetId }
            .toList()

    /** One honest sentence for under the button. Counts only what the rows say. */
    fun summary(rows: List<FleetDownloadRow>): String {
        if (rows.isEmpty()) return "Checking what is published…"
        val toGet = rows.count { it.action == FleetDownloadAction.DOWNLOAD }
        val toUpdate = rows.count { it.action == FleetDownloadAction.UPDATE }
        val unpublished = rows.count { it.action == FleetDownloadAction.NOT_PUBLISHED }
        val noSource = rows.count { it.action == FleetDownloadAction.NO_SOURCE }
        val parts = buildList {
            if (toGet > 0) add("$toGet to download")
            if (toUpdate > 0) add("$toUpdate to update")
            if (unpublished > 0) add("$unpublished with no APK published yet")
            if (noSource > 0) add("$noSource with no download source set up")
        }
        return if (parts.isEmpty()) "Everything that can be downloaded is installed and up to date." else parts.joinToString(", ") + "."
    }
}
