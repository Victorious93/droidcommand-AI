package ai.droidcommand.app.communications

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The owner's on/off for automatic draft checking. An interface so the screen's logic can be tested without an Android Context. */
interface DraftAutoCheck {
    fun isOn(): Boolean

    fun setOn(on: Boolean)
}

/**
 * Victorious Framework: the owner's switch for automatic checking. OFF by default. WorkManager's shortest period is 15
 * minutes and Android may delay it further (Doze, battery saver, app standby), so this is "every so often", never "instantly".
 * The setting is remembered here and the work is (re)scheduled from it, so it survives restarts.
 */
@Singleton
class DraftScheduler @Inject constructor(@ApplicationContext private val context: Context) : DraftAutoCheck {
    private val prefs = context.getSharedPreferences("reply_drafts", Context.MODE_PRIVATE)

    override fun isOn(): Boolean = prefs.getBoolean(KEY_AUTO, false)

    override fun setOn(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO, on) }
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DraftCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val NAME = "reply-drafts-check"
        const val KEY_AUTO = "auto_check"
    }
}
