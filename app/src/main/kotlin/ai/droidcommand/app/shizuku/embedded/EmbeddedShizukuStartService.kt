package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.app.MainActivity
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.shizuku.EmbeddedStartPolicy
import ai.droidcommand.shizuku.WirelessPairingInput
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Starts built-in Shizuku from a button or the reboot notification.
 * Boot itself uses [ShizukuBootReceiver] + goAsync, not this service.
 * Does not request Shizuku permission.
 */
@AndroidEntryPoint
class EmbeddedShizukuStartService : Service() {
    @Inject
    lateinit var starter: EmbeddedShizukuStarter

    @Inject
    lateinit var prefs: AdvancedPrefs

    @Inject
    lateinit var notifier: ShizukuStartNotifier

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_FROM_NOTIFICATION
        executor.execute {
            try {
                handle(action, intent)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handle(action: String, intent: Intent?) {
        when (action) {
            ACTION_START_WIRELESS -> {
                val code = intent?.getStringExtra(EXTRA_PAIR_CODE).orEmpty()
                val pairPort = intent?.getStringExtra(EXTRA_PAIR_PORT).orEmpty()
                val connectPort = intent?.getStringExtra(EXTRA_CONNECT_PORT).orEmpty()
                starter.startWireless(WirelessPairingInput(code, pairPort, connectPort))
            }
            ACTION_START_ROOT -> starter.startRoot()
            ACTION_START_PAIRED -> starter.startPaired()
            ACTION_FROM_NOTIFICATION -> startFromNotification()
        }
    }

    private fun startFromNotification() {
        val signals = starter.signals()
        if (EmbeddedStartPolicy.running(signals) || signals.rootAvailable || signals.wirelessAdbPaired) {
            EmbeddedShizukuBootRunner(this, starter, prefs, notifier).runNotificationTap()
            if (!EmbeddedStartPolicy.running(starter.signals()) && !signals.rootAvailable && !signals.wirelessAdbPaired) {
                openAdvanced()
            } else if (!EmbeddedStartPolicy.running(starter.signals()) && signals.wirelessAdbPaired && !signals.wifiConnected) {
                openAdvanced()
            }
            return
        }
        openAdvanced()
    }

    private fun openAdvanced() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_ADVANCED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_FROM_NOTIFICATION = "ai.droidcommand.app.shizuku.START_FROM_NOTIFICATION"
        const val ACTION_START_WIRELESS = "ai.droidcommand.app.shizuku.START_WIRELESS"
        const val ACTION_START_ROOT = "ai.droidcommand.app.shizuku.START_ROOT"
        const val ACTION_START_PAIRED = "ai.droidcommand.app.shizuku.START_PAIRED"
        const val ACTION_OPEN_ADVANCED = "ai.droidcommand.app.shizuku.OPEN_ADVANCED"
        const val EXTRA_PAIR_CODE = "pair_code"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_CONNECT_PORT = "connect_port"
    }
}
