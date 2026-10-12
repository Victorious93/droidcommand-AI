package ai.droidcommand.app.prefs

import ai.droidcommand.app.ui.chat.ToolbarLayout
import ai.droidcommand.app.ui.chat.ToolbarToggle
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Advanced settings that are not secret, kept in their own SharedPreferences file. Reads and writes are
 * synchronous on purpose: they are a few bytes and happen on user taps.
 */
class AdvancedPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun toolbarToggles(): Set<ToolbarToggle> = ToolbarLayout.parse(prefs.getString(KEY_TOOLBAR, null))

    fun setToolbarToggle(toggle: ToolbarToggle, visible: Boolean) {
        val next = toolbarToggles().let { if (visible) it + toggle else it - toggle }
        prefs.edit { putString(KEY_TOOLBAR, ToolbarLayout.serialize(next)) }
    }

    fun publicClipLimit(): Int =
        prefs.getInt(KEY_PUBLIC_LIMIT, DEFAULT_PUBLIC_LIMIT).takeIf { it in PUBLIC_LIMIT_CHOICES } ?: DEFAULT_PUBLIC_LIMIT

    fun setPublicClipLimit(limit: Int) {
        require(limit in PUBLIC_LIMIT_CHOICES) { "Unsupported public clipboard limit: $limit" }
        prefs.edit { putInt(KEY_PUBLIC_LIMIT, limit) }
    }

    companion object {
        const val FILE = "advanced_prefs"
        private const val KEY_TOOLBAR = "chat.toolbar"
        private const val KEY_PUBLIC_LIMIT = "clipboard.public.limit"
        val PUBLIC_LIMIT_CHOICES = listOf(10, 25, 50)
        const val DEFAULT_PUBLIC_LIMIT = 25
    }
}
