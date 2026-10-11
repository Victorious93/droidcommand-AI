package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
 *
 * The body is read on a helper thread while the calling thread polls: `HttpURLConnection.disconnect()` does not
 * reliably unblock a read already waiting on the socket (measured: a stalled read ran to the full read timeout),
 * so the caller cannot rely on closing the connection (and even `disconnect()` can block behind that read). Instead the caller stops waiting on its own, on
 * cancellation (within [pollMs]) or when fewer than [minBytesPerWindow] bytes arrive in a [stallMs] window (a
 * server dripping bytes often enough to keep resetting the socket timeout). Only the calling thread touches
 * [dest], so an abandoned helper can never write after this call has returned; it ends on its own when the
 * socket read times out or the connection is closed. Waiting for the status line and headers is bounded by the
 * connect/read timeouts only, not by [cancelled].
 */
class HttpsModelDownloader internal constructor(
    private val httpsOnly: Boolean,
    private val stallMs: Long = STALL_MS,
    private val minBytesPerWindow: Long = MIN_BYTES_PER_WINDOW,
    private val pollMs: Long = POLL_MS,
) : ModelDownloader {
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
            val chunks = LinkedBlockingQueue<Any>(QUEUE_CHUNKS)
            val abandoned = AtomicBoolean(false)
            val input = conn.inputStream
            thread(isDaemon = true, name = "model-download-reader") {
                try {
                    val buf = ByteArray(BUFFER_BYTES)
                    while (!abandoned.get()) {
                        val n = input.read(buf)
                        handOff(chunks, abandoned, if (n < 0) END else buf.copyOf(n))
                        if (n < 0) return@thread
                    }
                } catch (e: IOException) {
                    handOff(chunks, abandoned, e)
                }
            }
            try {
                java.io.FileOutputStream(dest, resuming).use { out ->
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        if (System.nanoTime() - windowStart >= stallMs * 1_000_000) {
                            if (windowBytes < minBytesPerWindow) {
                                throw IOException("Download stalled: under $minBytesPerWindow bytes in ${stallMs / 1000}s")
                            }
                            windowStart = System.nanoTime()
                            windowBytes = 0L
                        }
                        when (val item = chunks.poll(pollMs, TimeUnit.MILLISECONDS)) {
                            null -> Unit
                            END -> break
                            is IOException -> throw item
                            is ByteArray -> {
                                total += item.size
                                if (total > maxBytes) throw IOException("Download exceeds $maxBytes bytes")
                                out.write(item)
                                windowBytes += item.size
                                onBytes(item.size.toLong())
                            }
                        }
                    }
                }
            } finally {
                abandoned.set(true)
            }
        } finally {
            // disconnect() can itself block behind a read in progress, so never wait for it on this thread.
            thread(isDaemon = true, name = "model-download-disconnect") { conn.disconnect() }
        }
    }

    private fun handOff(chunks: LinkedBlockingQueue<Any>, abandoned: AtomicBoolean, item: Any) {
        while (!abandoned.get() && !chunks.offer(item, pollMs, TimeUnit.MILLISECONDS)) Unit
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val STALL_MS = 30_000L
        const val MIN_BYTES_PER_WINDOW = 16L * 1024
        const val POLL_MS = 250L
        const val QUEUE_CHUNKS = 4
        val END = Any()
    }
}
