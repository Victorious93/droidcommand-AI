package ai.droidcommand.app.fleet.install

import ai.droidcommand.fleet.update.FleetAutoUpdateMode
import ai.droidcommand.fleet.update.FleetAutoUpdateRules
import ai.droidcommand.fleet.update.UpdateDecision
import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The owner's "keep my fleet apps up to date" switch. **Off by default**: nothing is checked or installed in the
 * background until the owner turns it on. Even on, it only updates fleet apps already on this phone (never a
 * first install) and never pairs or trusts anything.
 */
object FleetAutoUpdate {
    private const val PREFS = "fleet_auto_update"
    private const val KEY = "enabled"
    private const val WORK = "fleet_auto_update"
    private const val MODE_PREFIX = "mode_"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, enabled) }
        reschedule(context)
    }

    /** The owner's own choice for one app, or null when they never made one (it then follows the global switch). */
    fun storedMode(context: Context, fleetId: String): FleetAutoUpdateMode? =
        FleetAutoUpdateMode.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE_PREFIX + fleetId, null))

    /** The mode in force for one app: its own choice, else stable when the global switch is on, else off. */
    fun effectiveMode(context: Context, fleetId: String): FleetAutoUpdateMode =
        FleetAutoUpdateRules.effective(storedMode(context, fleetId), isEnabled(context))

    /** Saves the owner's explicit choice for one app (including Off) and starts or stops the background job to match. */
    fun setMode(context: Context, fleetId: String, mode: FleetAutoUpdateMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(MODE_PREFIX + fleetId, mode.name) }
        reschedule(context)
    }

    /** True when the global switch is on or any app has a choice other than Off. */
    fun anyActive(context: Context): Boolean =
        FleetAutoUpdateRules.needsSchedule(isEnabled(context), FleetSources.all.mapNotNull { storedMode(context, it.fleetId) })

    private fun reschedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!anyActive(context)) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<FleetUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Checks every installed fleet app that has a release source and installs a newer published APK. */
class FleetUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!FleetAutoUpdate.anyActive(applicationContext)) return Result.success()
        val installer = FleetAppInstaller(applicationContext)
        if (!installer.canInstall()) return Result.success()
        var failed = false
        for (source in FleetSources.all) {
            if (isStopped) return Result.retry()
            val mode = FleetAutoUpdate.effectiveMode(applicationContext, source.fleetId)
            if (mode == FleetAutoUpdateMode.OFF) continue
            if (!installer.isInstalled(source.packageName)) continue
            val decision = installer.check(source, mode.includesPrereleases)
            if (decision !is UpdateDecision.Update) continue
            runCatching {
                val apk = installer.download(source, decision.asset)
                installer.install(source, apk, silentIfAllowed = true)
            }.onFailure { failed = true }
        }
        return if (failed) Result.retry() else Result.success()
    }
}
