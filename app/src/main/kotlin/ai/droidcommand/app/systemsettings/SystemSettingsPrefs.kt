package ai.droidcommand.app.systemsettings

import ai.droidcommand.systemsettings.AccessConfig
import ai.droidcommand.systemsettings.AccessKind
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The owner's System Settings access policy. Standard permissions are always allowed; Shizuku is allowed by default; root, other users and
 * the assistant are all OFF until the owner turns each on. Synchronous on purpose (a few bytes, written on a tap).
 */
@Singleton
class SystemSettingsPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun config(): AccessConfig = AccessConfig(
        allowed = buildSet {
            add(AccessKind.STANDARD)
            if (prefs.getBoolean(KEY_SHIZUKU, true)) add(AccessKind.SHIZUKU)
            if (prefs.getBoolean(KEY_ROOT, false)) add(AccessKind.ROOT)
        },
        allowOtherUsers = prefs.getBoolean(KEY_OTHER_USERS, false),
        assistantEnabled = prefs.getBoolean(KEY_ASSISTANT, false),
    )

    fun setShizukuAllowed(on: Boolean) = prefs.edit { putBoolean(KEY_SHIZUKU, on) }
    fun setRootAllowed(on: Boolean) = prefs.edit { putBoolean(KEY_ROOT, on) }
    fun setOtherUsersAllowed(on: Boolean) = prefs.edit { putBoolean(KEY_OTHER_USERS, on) }
    fun setAssistantEnabled(on: Boolean) = prefs.edit { putBoolean(KEY_ASSISTANT, on) }

    private companion object {
        const val FILE = "system_settings_access"
        const val KEY_SHIZUKU = "allow.shizuku"
        const val KEY_ROOT = "allow.root"
        const val KEY_OTHER_USERS = "allow.other_users"
        const val KEY_ASSISTANT = "allow.assistant"
    }
}
