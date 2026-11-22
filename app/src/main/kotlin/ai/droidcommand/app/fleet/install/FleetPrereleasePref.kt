package ai.droidcommand.app.fleet.install

import android.content.Context
import androidx.core.content.edit

/**
 * The owner's "include pre-releases" choice for the Fleet download list. **Off by default.** Several of the owner's apps publish
 * only debug-signed pre-releases, so without this their download buttons read "Not published". It affects the manual download
 * list only: background auto-update ([FleetAutoUpdate]) never reads it and stays on stable releases. Android still refuses an
 * update signed with a different key than the installed app, and the owner still confirms every install.
 */
object FleetPrereleasePref {
    private const val PREFS = "fleet_download_prefs"
    private const val KEY = "include_prereleases"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, enabled) }
    }
}
