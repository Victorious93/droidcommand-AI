package ai.droidcommand.fleet.update

/** One fleet app and the GitHub repo its APKs are published from. [repo] is `owner/name`. */
data class FleetAppSource(val fleetId: String, val displayName: String, val packageName: String, val repo: String)

sealed class UpdateDecision {
    /** Nothing published we can install (no release, or no `.apk` asset). Not an error. */
    data class NothingPublished(val reason: String) : UpdateDecision()

    data class UpToDate(val version: String) : UpdateDecision()

    /** Not installed: the owner must tap the system installer once. */
    data class Install(val release: FleetRelease, val asset: ReleaseAsset, val isDebugBuild: Boolean) : UpdateDecision()

    data class Update(
        val from: String,
        val release: FleetRelease,
        val asset: ReleaseAsset,
        val isDebugBuild: Boolean,
    ) : UpdateDecision()

    /** Installed version or release tag cannot be compared, so no update is claimed. */
    data class Unknown(val reason: String) : UpdateDecision()
}

/**
 * Decides what to do for one app. Pure: give it the installed `versionName`, the parsed releases and the phone's ABIs.
 *
 * Drafts are never offered. Pre-releases are skipped unless the owner opted in with [plan]'s `includePrereleases`; that
 * default is `false` on purpose so background auto-update keeps installing stable releases only.
 */
object UpdatePlanner {
    fun plan(
        source: FleetAppSource,
        installedVersionName: String?,
        installed: Boolean,
        releases: List<FleetRelease>,
        supportedAbis: List<String>,
        includePrereleases: Boolean = false,
    ): UpdateDecision {
        val candidates = releases.filter { !it.draft && (includePrereleases || !it.prerelease) }
        val usable = candidates.firstNotNullOfOrNull { release ->
            chooseAsset(release.assets.filter { DownloadPolicy.isAllowedAsset(it.url, source.repo) }, supportedAbis)
                ?.let { release to it }
        } ?: return UpdateDecision.NothingPublished(
            if (candidates.isEmpty()) "No published release." else "The latest release has no installable APK.",
        )
        val (release, choice) = usable
        if (!installed) return UpdateDecision.Install(release, choice.asset, choice.debug)
        val cmp = VersionCompare.compare(installedVersionName, release.tag)
            ?: return UpdateDecision.Unknown("Cannot compare '${installedVersionName.orEmpty()}' with '${release.tag}'.")
        return if (cmp < 0) {
            UpdateDecision.Update(installedVersionName.orEmpty(), release, choice.asset, choice.debug)
        } else {
            UpdateDecision.UpToDate(installedVersionName.orEmpty())
        }
    }

    private data class Choice(val asset: ReleaseAsset, val debug: Boolean)

    /** Release builds before debug builds; a matching ABI before a universal APK; never a different ABI. */
    private fun chooseAsset(assets: List<ReleaseAsset>, abis: List<String>): Choice? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val known = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "arm64", "armv7")
        fun abiOf(name: String) = known.firstOrNull { name.contains(it, ignoreCase = true) }
        fun matchesPhone(name: String): Boolean {
            val abi = abiOf(name) ?: return true
            val normal = when (abi) {
                "arm64" -> "arm64-v8a"
                "armv7" -> "armeabi-v7a"
                else -> abi
            }
            return abis.any { it.equals(normal, ignoreCase = true) }
        }
        val fits = apks.filter { matchesPhone(it.name) }
        fun rank(a: ReleaseAsset): Int {
            val abiIndex = abiOf(a.name)?.let { abi ->
                abis.indexOfFirst { phone ->
                    phone.equals(
                        if (abi == "arm64") {
                            "arm64-v8a"
                        } else if (abi == "armv7") {
                            "armeabi-v7a"
                        } else {
                            abi
                        },
                        true,
                    )
                }
            } ?: Int.MAX_VALUE / 2 // universal APK: after any specific match
            return abiIndex
        }
        val release = fits.filter { !it.name.contains("debug", true) }.minByOrNull(::rank)
        if (release != null) return Choice(release, false)
        return fits.minByOrNull(::rank)?.let { Choice(it, true) }
    }
}
