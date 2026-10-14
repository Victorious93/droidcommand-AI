package ai.droidcommand.app.fleet

import android.content.Context

/**
 * PackageManager view of [FleetCatalog] package names. Same check
 * `AndroidCompanionRegistry.isCompanionInstalled` uses; this copy lives in `:app`
 * because `:core-companion` is still excluded from the default build.
 *
 * A true result means the APK is present, not that a binder is connected.
 */
class PackageFleetProbe(private val context: Context) {
    fun isInstalled(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
