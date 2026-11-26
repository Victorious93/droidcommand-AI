package ai.droidcommand.app.communications

import ai.droidcommand.communications.ContentRefs
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit

/**
 * Victorious Framework: remembers exactly which Android read grants the share dialog took, so that stopping a share releases
 * those and ONLY those. Other features of this app (chat attachments, model import, first-run, the brain) also hold persisted
 * grants of their own and must never be released from here.
 *
 * Limit: Android keeps one persisted grant per URI. If the owner picked the very same folder or file for another feature as well,
 * releasing it here releases it for that feature too; the ledger cannot tell. Unverified on a device. COMPILES; NEVER RUN.
 */
class PickedAccessLedger(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences("picked_access", Context.MODE_PRIVATE)

    /** Takes persistable read access for [uri] and records it. A provider that refuses simply leaves nothing recorded. */
    fun keep(uri: Uri) {
        val took = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        val ref = ContentRefs.canonical(uri.toString())
        if (took && ref != null) prefs.edit { putStringSet(KEY, taken() + ref) }
    }

    /** Releases the recorded grants among [refs] (canonical references no active share covers). Anything not recorded is left alone. */
    fun release(refs: Set<String>) {
        val mine = taken().intersect(refs)
        if (mine.isEmpty()) return
        runCatching {
            resolver.persistedUriPermissions.forEach { p ->
                if (ContentRefs.canonical(p.uri.toString()) in mine) {
                    runCatching { resolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }
        prefs.edit { putStringSet(KEY, taken() - mine) }
    }

    // A fresh copy every time: a set returned by SharedPreferences must never be modified or stored back as-is.
    private fun taken(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private companion object {
        const val KEY = "taken"
    }
}
