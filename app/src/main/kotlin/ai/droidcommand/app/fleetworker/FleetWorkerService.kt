package ai.droidcommand.app.fleetworker

import ai.droidcommand.app.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps this phone connected to the fleet coordinator, only while the owner has the worker switched on and the phone is paired.
 * It is a visible foreground service with a notification that has a **Switch off** button, and it stops itself the moment the
 * switch goes off, the phone is unpaired or revoked, or the coordinator rejects it.
 *
 * Foreground type `specialUse`: Android documents `dataSync` as capped at 6 hours per 24 on Android 15, which does not fit a
 * connection that stays up until the owner turns it off; `specialUse` has no such cap listed but is reviewed by Google Play
 * if the app is ever published there (see docs/FLEET_WORKER.md). It does not restart itself after a reboot; the owner's switch
 * stays on and the service comes back the next time the app is opened.
 *
 * Never run on a device.
 */
@AndroidEntryPoint
class FleetWorkerService : Service() {
    @Inject lateinit var controller: FleetWorkerController

    @Volatile private var stopping = false

    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SWITCH_OFF) {
            stopping = true
            controller.setWorkerEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Must follow startForegroundService() quickly, before anything that could block.
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!controller.shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread?.isAlive != true) {
            stopping = false
            thread = Thread(
                {
                    try {
                        controller.runLoopForService { stopping }
                    } finally {
                        stopSelf()
                    }
                },
                "fleet-worker",
            ).also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        thread?.interrupt()
        controller.refresh()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun notification(): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(
            this, 1, Intent(this, FleetWorkerService::class.java).setAction(ACTION_SWITCH_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Fleet worker is on")
            .setContentText("Taking jobs from your fleet coordinator. Switch it off any time.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Switch off", off)
            .build()
    }

    companion object {
        const val ACTION_SWITCH_OFF = "ai.droidcommand.action.FLEET_WORKER_SWITCH_OFF"
        const val CHANNEL_ID = "fleet_worker"
        const val NOTIFICATION_ID = 7201

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Fleet worker", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while this phone is connected to your fleet coordinator."
                },
            )
        }
    }
}
