package ai.droidcommand.app.fleet.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process "this fleet app was just installed" signal, so the Fleet screen can refresh and offer to pair it. */
object FleetInstallEvents {
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val installed: SharedFlow<String> = mutable.asSharedFlow()

    fun emitInstalled(fleetId: String) {
        mutable.tryEmit(fleetId)
    }
}

/**
 * Receives PackageInstaller results. If Android needs the owner's confirmation, that screen is shown through a
 * notification (a background app cannot reliably open it directly). On success it only reports it: pairing
 * stays an explicit owner step on the Fleet screen.
 */
class FleetInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fleetId = intent.getStringExtra(EXTRA_FLEET_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME) ?: fleetId
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                notify(context, fleetId.hashCode(), "Confirm install: $name", "Tap to continue in Android's installer.", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                FleetInstallEvents.emitInstalled(fleetId)
                notify(context, fleetId.hashCode(), "$name installed", "Open the Fleet tab to pair it.", null)
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                notify(context, fleetId.hashCode(), "$name was not installed", reason.ifBlank { "Android refused the install." }, null)
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String, open: Intent?) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Fleet app updates", NotificationManager.IMPORTANCE_DEFAULT))
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            if (open != null) {
                builder.setContentIntent(
                    PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            manager.notify(id, builder.build())
        }
    }

    companion object {
        const val EXTRA_FLEET_ID = "fleet_id"
        const val EXTRA_NAME = "fleet_name"
        private const val CHANNEL = "fleet_updates"
    }
}
