package ai.droidcommand.app.activity

import ai.droidcommand.activity.ActivityLog
import ai.droidcommand.activity.WaitingNotices
import ai.droidcommand.app.MainActivity
import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the owner "<bot> is waiting for you" when this app is in the background and a bot, Pilot or Plan run is
 * stuck on an approval, so a blocked run is not left waiting unseen until its approval times out.
 *
 * It only ever *tells*: answering still happens in the approval dialog inside the app, and the notification says
 * nothing about what is being asked (no tool name, no arguments), also on the lock screen. It stays quiet while
 * any of the app's screens is on display, and it does nothing when notifications are not allowed; asking for that
 * permission is the first-run setup's job, not this class's. Showing is delayed a moment so that rotating the
 * screen, which briefly leaves no screen on display, does not flash a notification.
 *
 * The decision of who is waiting is [WaitingNotices] (JVM-tested); this class is the Android shell around it and has
 * never run on a device.
 */
@Singleton
class ActivityNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ActivityLog,
) {
    private val lock = Any()
    private val notices = WaitingNotices()
    private val shown = mutableSetOf<String>()
    private var startedScreens = 0
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val syncLater = Runnable { sync() }

    /** Starts listening. Safe to call more than once. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    synchronized(lock) { startedScreens += 1 }
                    // Coming back to the app: take the notifications down at once.
                    handler.removeCallbacks(syncLater)
                    sync()
                }

                override fun onActivityStopped(activity: Activity) {
                    synchronized(lock) { startedScreens = (startedScreens - 1).coerceAtLeast(0) }
                    schedule()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        log.addListener { event -> if (notices.onEvent(event)) schedule() }
    }

    private fun schedule() {
        handler.removeCallbacks(syncLater)
        handler.postDelayed(syncLater, SHOW_DELAY_MS)
    }

    /** Makes the shown notifications match who is waiting, if the app is in the background. */
    private fun sync() {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            synchronized(lock) {
                val wanted = if (startedScreens > 0 || !canNotify(manager)) emptyList() else notices.current()
                val wantedIds = wanted.map { it.runId }.toSet()
                (shown - wantedIds).forEach { manager.cancel(notificationId(it)) }
                wanted.filter { it.runId !in shown }.forEach { post(manager, it) }
                shown.clear()
                shown.addAll(wantedIds)
            }
        }
    }

    private fun canNotify(manager: NotificationManagerCompat): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    private fun post(manager: NotificationManagerCompat, waiting: WaitingNotices.Waiting) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // What is being asked stays out of the notification: it can be shown on a locked screen.
        val generic = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Something is waiting for you")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("${waiting.actorName} is waiting for you")
            .setContentText("Open DroidCommand AI to allow or decline.")
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(notificationId(waiting.runId), notification)
        } catch (_: SecurityException) {
            // Notifications turned off between the check and the post: nothing to do.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Waiting for you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when a helper bot, Pilot or Plan run needs your answer."
            },
        )
    }

    private fun notificationId(runId: String): Int = NOTIFICATION_BASE + (runId.hashCode() and 0xFFFF)

    companion object {
        const val CHANNEL_ID = "activity_waiting"
        private const val NOTIFICATION_BASE = 24_000
        private const val SHOW_DELAY_MS = 1_500L
    }
}
