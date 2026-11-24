package ai.droidcommand.app

import ai.droidcommand.app.activity.ActivityNotifier
import ai.droidcommand.app.deviceconnect.DeviceConnectRuntime
import ai.droidcommand.app.fleet.AndroidFleetThemeClient
import ai.droidcommand.communications.CommunicationsAgentTools
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

    @Inject
    lateinit var deviceConnectRuntime: DeviceConnectRuntime

    @Inject
    lateinit var activityNotifier: ActivityNotifier

    @Inject
    lateinit var communicationsAgentTools: CommunicationsAgentTools

    override fun onCreate() {
        super.onCreate()
        // Listeners only — never request permission or run a privileged command at start.
        shizukuManager.start()
        // Reader only: query VictorLaunch's theme provider and watch for live updates.
        fleetThemeClient.start()
        // Listener stays down unless the owner left "keep waiting" on.
        deviceConnectRuntime.startIfKeptOn()
        // Listener only: tells the owner when a run is waiting on them while the app is in the background.
        activityNotifier.start()
        // Registers the communications tools only if the owner switched them on; off by default, so a fresh install offers none.
        communicationsAgentTools.apply()
    }
}
