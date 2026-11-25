package ai.droidcommand.app.communications

import ai.droidcommand.communications.ContentRefs
import ai.droidcommand.communications.MediaGateway
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Victorious Framework: reads a file the owner picked with Android's own folder or file picker. It holds no broad storage
 * permission: the system lets this app read only what the owner chose (the persisted grants from `takePersistableUriPermission`),
 * so a reference outside those picks cannot be read even if something asked. The file-share engine has ALREADY decided yes before
 * this is called; this is the second lock, not the first.
 *
 * Fails closed: an unrecognised reference, a missing grant, an unknown size or any error returns null and nothing is read.
 * Plain file paths are not served (that is File-Manager's job, and its bridge is still a stub). COMPILES; NEVER RUN on a device.
 */
class ContentResolverMediaGateway(private val context: Context) : MediaGateway {
    override fun open(normalizedPath: String): MediaGateway.Blob? {
        val ref = ContentRefs.parse(normalizedPath) ?: return null
        val uri = Uri.parse(ref.canonical)
        return try {
            val resolver = context.contentResolver
            var name = "attachment"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { n -> name = n } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { i -> size = c.getLong(i) }
                }
            }
            if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size <= 0) return null
            val head = resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(HEAD_BYTES)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            } ?: return null
            MediaGateway.Blob(name, resolver.getType(uri), size, head)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        /** Enough for every signature the validator knows. */
        const val HEAD_BYTES = 512
    }
}
