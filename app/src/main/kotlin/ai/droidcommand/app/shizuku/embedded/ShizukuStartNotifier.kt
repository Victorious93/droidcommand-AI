package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.app.MainActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Boot fallback when this phone cannot start Shizuku by itself. The tap
 * opens [EmbeddedShizukuStartService] which starts if it can, or the
 * Advanced tab if it still needs the pair code.
 */
class ShizukuStartNotifier(private val context: Context) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Shizuku start", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to start built-in Shizuku after a reboot."
            },
        )
    }

    fun postOneTapStart() {
        ensureChannel()
        val start = PendingIntent.getService(
            context,
            1,
            Intent(context, EmbeddedShizukuStartService::class.java).setAction(EmbeddedShizukuStartService.ACTION_FROM_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).setAction(EmbeddedShizukuStartService.ACTION_OPEN_ADVANCED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Start Shizuku")
            .setContentText("Shizuku stopped after the reboot. Tap to start it.")
            .setContentIntent(start)
            .addAction(0, "Start", start)
            .addAction(0, "Open Advanced", open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — the user can still open Advanced.
        }
    }

    companion object {
        const val CHANNEL_ID = "shizuku_start"
        const val NOTIFICATION_ID = 23011
    }
}
