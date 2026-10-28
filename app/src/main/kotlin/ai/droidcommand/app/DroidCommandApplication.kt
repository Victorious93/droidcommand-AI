package ai.droidcommand.app

import ai.droidcommand.app.fleet.AndroidFleetThemeClient
import ai.droidcommand.shizuku.ShizukuManager
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Entry point for Hilt's generated dependency graph — see [ai.droidcommand.app.di.AppModule]. */
@HiltAndroidApp
class DroidCommandApplication : Application() {
    @Inject
    lateinit var shizukuManager: ShizukuManager

    @Inject
    lateinit var fleetThemeClient: AndroidFleetThemeClient

    override fun onCreate() {
        super.onCreate()
        // Listeners only — never request permission or run a privileged command at start.
        shizukuManager.start()
        // Reader only: query VictorLaunch's theme provider and watch for live updates.
        fleetThemeClient.start()
    }
}
