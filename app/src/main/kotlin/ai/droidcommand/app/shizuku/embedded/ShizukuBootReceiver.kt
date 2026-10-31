package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.app.prefs.AdvancedPrefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * `BOOT_COMPLETED` only. Start on boot stays off until the user flips the
 * Advanced-tab toggle. This receiver does not grant any permission. Work
 * stays on [goAsync] so Android 8+ does not need a background service start.
 */
@AndroidEntryPoint
class ShizukuBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var prefs: AdvancedPrefs

    @Inject
    lateinit var starter: EmbeddedShizukuStarter

    @Inject
    lateinit var notifier: ShizukuStartNotifier

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) return
        if (!prefs.startShizukuOnBoot()) return
        val pending = goAsync()
        thread(name = "shizuku-boot") {
            try {
                EmbeddedShizukuBootRunner(context, starter, prefs, notifier).runBoot()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
