package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Network seam: writes the body of [url] to [dest], refusing to let [dest] exceed [maxBytes]. Throws [IOException]
 * on failure or cancellation. If [dest] already holds bytes from an earlier interrupted call, an implementation
 * should resume after them and append; [onBytes] receives only the bytes written by this call. The caller verifies
 * the finished file, so a resumed prefix is never trusted on its own.
 */
fun interface ModelDownloader {
    fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean)
}

/**
 * https-only download with connect/read timeouts, a hard byte cap and cancellation checked between reads.
 * Redirects are followed by `HttpURLConnection` only when they stay on https (it never follows an
 * https-to-http redirect), which the Hugging Face file URLs rely on (they redirect to a CDN host). The caller,
 * not this class, decides whether the bytes are trusted: [ModelRepository.install] checks size and SHA-256.
 */
class HttpsModelDownloader internal constructor(private val httpsOnly: Boolean) : ModelDownloader {
    constructor() : this(true)

    override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        require(!httpsOnly || url.startsWith("https://")) { "https only" }
        val existing = if (dest.isFile) dest.length() else 0L
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = conn.responseCode
            val resuming = when {
                code == 206 && existing > 0 && conn.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true -> true
                code == 200 -> false
                code == 416 -> {
                    dest.delete()
                    throw IOException("Server rejected the resume; the partial file was discarded, try again")
                }
                else -> throw IOException("HTTP $code for ${URI(url).host}")
            }
            var total = if (resuming) existing else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest, resuming).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Download exceeds $maxBytes bytes")
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
    }
}
