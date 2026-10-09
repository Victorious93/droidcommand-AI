package ai.droidcommand.voice.neural

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Process keep-alive and the always-visible indicator for wake-word listening. NEVER COMPILED OR RUN.
 *
 * It does NOT own the detector: `WakeWordController` (core-voice) does, so there is exactly one owner of the
 * microphone. This service only (1) runs as a microphone-type foreground service with an ongoing notification
 * that has a Stop action, and (2) holds the controller's microphone while the screen is off unless the user
 * opted in to listening then. Start it only after the user enabled wake word and RECORD_AUDIO is granted;
 * Android 12+ refuses to start a microphone foreground service from the background, and that restriction is
 * NOT handled here beyond catching the failure in [start].
 */
class WakeWordService : Service() {
    private var screenReceiver: BroadcastReceiver? = null
    private var heldForScreenOff = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWordHost.onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerScreenReceiver(intent?.getBooleanExtra(EXTRA_LISTEN_WHEN_SCREEN_OFF, false) ?: false)
        return START_NOT_STICKY // never resurrect an always-on microphone after a kill
    }

    override fun onDestroy() {
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        if (heldForScreenOff) WakeWordHost.release?.invoke()
        heldForScreenOff = false
        super.onDestroy()
    }

    private fun registerScreenReceiver(listenWhenScreenOff: Boolean) {
        if (listenWhenScreenOff || screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> if (!heldForScreenOff) {
                        heldForScreenOff = true
                        WakeWordHost.hold?.invoke()
                    }
                    Intent.ACTION_SCREEN_ON -> if (heldForScreenOff) {
                        heldForScreenOff = false
                        WakeWordHost.release?.invoke()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening for the wake word")
            .setContentText("The microphone is on. Audio is not recorded or sent anywhere.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "ai.droidcommand.voice.neural.STOP_WAKE_WORD"
        const val EXTRA_LISTEN_WHEN_SCREEN_OFF = "listen_when_screen_off"

        /** Starts the service; returns false (and starts nothing) if the system refuses. */
        fun start(context: Context, listenWhenScreenOff: Boolean): Boolean = try {
            context.startForegroundService(
                Intent(context, WakeWordService::class.java).putExtra(EXTRA_LISTEN_WHEN_SCREEN_OFF, listenWhenScreenOff),
            )
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}

/**
 * Process-local hooks the app sets so the service can reach its `WakeWordController` without a dependency on
 * it: [hold]/[release] map to the controller's counted microphone holds; [onStopRequested] is called when the
 * user taps Stop in the notification (the app should turn wake word off).
 */
object WakeWordHost {
    @Volatile var hold: (() -> Unit)? = null

    @Volatile var release: (() -> Unit)? = null

    @Volatile var onStopRequested: (() -> Unit)? = null
}
