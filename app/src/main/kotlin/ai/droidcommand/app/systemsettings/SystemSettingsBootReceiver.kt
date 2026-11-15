package ai.droidcommand.app.systemsettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Optional "reapply after reboot" for System Settings profiles. Runs on BOOT_COMPLETED only, which Android delivers after the user unlocks, so
 * the credential-encrypted storage the history and replay state live in is readable. Does nothing for a profile unless the owner turned reapply
 * on for it. Never grants a permission, never starts or elevates a backend, and keeps its work on goAsync with a hard time budget.
 */
@AndroidEntryPoint
class SystemSettingsBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var runtime: SystemSettingsRuntime

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (!um.isUserUnlocked) return
        if (runtime.replay.records().none { it.enabled }) return
        val pending = goAsync()
        thread(name = "system-settings-boot") {
            try {
                runtime.recoverInterrupted()
                runtime.replay.runAfterBoot(runtime.bootId())
            } finally {
                pending.finish()
            }
        }
    }
}
