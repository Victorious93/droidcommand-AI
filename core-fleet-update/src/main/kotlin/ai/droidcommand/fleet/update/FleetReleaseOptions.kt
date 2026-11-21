package ai.droidcommand.fleet.update

/** What kind of build a release is, as the owner reads it in the version menu. */
enum class FleetReleaseChannel(val label: String) {
    STABLE("Stable"),
    DEBUG("Debug"),
    PRE_RELEASE("Pre-release (unstable)"),
    PRE_RELEASE_DEBUG("Pre-release, debug"),
    ;

    companion object {
        fun of(prerelease: Boolean, debug: Boolean): FleetReleaseChannel = when {
            prerelease && debug -> PRE_RELEASE_DEBUG
            prerelease -> PRE_RELEASE
            debug -> DEBUG
            else -> STABLE
        }
    }
}

/** How a release's tag compares with the version installed on the phone. */
enum class FleetVersionRelation(val label: String?) {
    /** The app is not on the phone, so there is nothing to compare with. */
    NOT_INSTALLED(null),
    NEWER("Newer than installed"),
    SAME("Installed version"),
    OLDER("Older than installed"),

    /** The tag or the installed version is not a plain number sequence, so no order is claimed. */
    NOT_COMPARABLE("Can't compare"),
}

/** One entry of an app's version menu: a published release, whether this phone has an APK for it, and how it compares. */
data class FleetReleaseOption(
    val tag: String,
    val channel: FleetReleaseChannel,
    /** The APK this phone would download for [tag]. Null means the release has no APK that fits this phone. */
    val asset: ReleaseAsset?,
    val relation: FleetVersionRelation,
) {
    val installable: Boolean get() = asset != null

    /** Android normally refuses to install an older version over a newer one, so the UI warns before offering it. */
    val isDowngrade: Boolean get() = relation == FleetVersionRelation.OLDER

    val label: String
        get() = listOfNotNull(
            tag,
            channel.label,
            relation.label,
            if (asset == null) "no APK for this phone" else null,
        ).joinToString(" · ")
}

/**
 * Builds the version menu for one app from every published release, newest first as GitHub lists them. Unlike
 * [UpdatePlanner] it does not hide pre-releases or older releases: the owner chooses. Drafts are the only thing left out.
 */
object FleetReleaseOptions {
    /** Shown when an app has no releases at all. */
    const val NONE_LABEL = "No releases published"

    fun build(
        source: FleetAppSource,
        installedVersionName: String?,
        installed: Boolean,
        releases: List<FleetRelease>,
        supportedAbis: List<String>,
    ): List<FleetReleaseOption> = releases
        .filter { !it.draft }
        .distinctBy { it.tag }
        .map { release ->
            val choice = UpdatePlanner.chooseAsset(
                release.assets.filter { DownloadPolicy.isAllowedAsset(it.url, source.repo) },
                supportedAbis,
            )
            FleetReleaseOption(
                tag = release.tag,
                channel = FleetReleaseChannel.of(release.prerelease, choice?.debug == true),
                asset = choice?.asset,
                relation = relationOf(installed, installedVersionName, release.tag),
            )
        }

    private fun relationOf(installed: Boolean, installedVersionName: String?, tag: String): FleetVersionRelation {
        if (!installed) return FleetVersionRelation.NOT_INSTALLED
        val cmp = VersionCompare.compare(installedVersionName, tag) ?: return FleetVersionRelation.NOT_COMPARABLE
        return when {
            cmp < 0 -> FleetVersionRelation.NEWER
            cmp == 0 -> FleetVersionRelation.SAME
            else -> FleetVersionRelation.OLDER
        }
    }
}
