package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Network seam: writes the body of [url] to [dest], refusing to write more than [maxBytes]. Throws [IOException] on failure or cancellation. */
fun interface ModelDownloader {
    fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean)
}

/**
 * https-only download with connect/read timeouts, a hard byte cap and cancellation checked between reads.
 * Redirects are followed by `HttpURLConnection` only when they stay on https (it never follows an
 * https-to-http redirect), which the Hugging Face file URLs rely on (they redirect to a CDN host). The caller,
 * not this class, decides whether the bytes are trusted: [ModelRepository.install] checks size and SHA-256.
 */
class HttpsModelDownloader : ModelDownloader {
    override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        require(url.startsWith("https://")) { "https only" }
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for ${URI(url).host}")
            var total = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
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
