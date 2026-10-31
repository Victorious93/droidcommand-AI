package ai.droidcommand.app.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether the first-run setup has been finished or skipped.
 * A few bytes in SharedPreferences; reads and writes stay on the tap that
 * changes them. Completing setup does not grant permissions or pair anything.
 */
class FirstRunPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete() {
        prefs.edit { putBoolean(KEY_COMPLETE, true) }
    }

    fun reset() {
        prefs.edit { putBoolean(KEY_COMPLETE, false) }
    }

    companion object {
        const val FILE = "first_run_prefs"
        const val KEY_COMPLETE = "first_run.complete"
    }
}
