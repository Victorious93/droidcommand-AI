package ai.droidcommand.app.systemsettings

import ai.droidcommand.systemsettings.PlatformSettingsAccess
import ai.droidcommand.systemsettings.SettingsTable
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * The SettingsProvider through the public ContentResolver / Settings.* APIs. Reads need no special access; System writes need the
 * "Modify system settings" special access (WRITE_SETTINGS) and Secure/Global writes need WRITE_SECURE_SETTINGS (granted once over adb).
 * Only the user this app runs as is reachable.
 */
class AndroidPlatformSettings(private val context: Context) : PlatformSettingsAccess {
    private val resolver get() = context.contentResolver

    /** UserHandle.getIdentifier() is hidden; the user id is the uid divided by the per-user range (100000). */
    override val currentUserId: Int = Process.myUid() / PER_USER_RANGE

    override fun getString(table: SettingsTable, name: String): String? = when (table) {
        SettingsTable.SYSTEM -> Settings.System.getString(resolver, name)
        SettingsTable.SECURE -> Settings.Secure.getString(resolver, name)
        SettingsTable.GLOBAL -> Settings.Global.getString(resolver, name)
    }

    override fun listAll(table: SettingsTable): Map<String, String> {
        val cursor = resolver.query(uriOf(table), arrayOf("name", "value"), null, null, null)
            ?: throw UnsupportedOperationException("The settings provider returned no cursor.")
        return cursor.use { c ->
            val out = LinkedHashMap<String, String>()
            val nameIdx = c.getColumnIndexOrThrow("name")
            val valueIdx = c.getColumnIndexOrThrow("value")
            while (c.moveToNext()) {
                val n = c.getString(nameIdx) ?: continue
                out[n] = c.getString(valueIdx) ?: ""
            }
            out
        }
    }

    override fun putString(table: SettingsTable, name: String, value: String): Boolean = when (table) {
        SettingsTable.SYSTEM -> Settings.System.putString(resolver, name, value)
        SettingsTable.SECURE -> Settings.Secure.putString(resolver, name, value)
        SettingsTable.GLOBAL -> Settings.Global.putString(resolver, name, value)
    }

    override fun delete(table: SettingsTable, name: String): Int = resolver.delete(uriOf(table), "name=?", arrayOf(name))

    override fun canWrite(table: SettingsTable): Boolean = when (table) {
        SettingsTable.SYSTEM -> Settings.System.canWrite(context)
        SettingsTable.SECURE, SettingsTable.GLOBAL ->
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    override fun missingPermissionHint(table: SettingsTable): String = when (table) {
        SettingsTable.SYSTEM -> "Allow 'Modify system settings' for this app (see the Access tab)."
        else -> "Grant WRITE_SECURE_SETTINGS over adb (see the Access tab)."
    }

    private fun uriOf(table: SettingsTable): Uri = when (table) {
        SettingsTable.SYSTEM -> Settings.System.CONTENT_URI
        SettingsTable.SECURE -> Settings.Secure.CONTENT_URI
        SettingsTable.GLOBAL -> Settings.Global.CONTENT_URI
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
