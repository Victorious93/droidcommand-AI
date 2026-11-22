package ai.droidcommand.fleet.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A downloaded, verified APK on disk. [sha256] is what was actually received. */
data class DownloadedApk(val file: File, val sha256: String, val digestChecked: Boolean)

/** The caller asked to stop. What was downloaded so far is kept, so the next call resumes it. */
class ApkDownloadStopped : IOException("Stopped")

/**
 * Downloads one fleet APK, resumably. Blocking: call off the main thread.
 *
 * Safety rules, all enforced here rather than left to the caller (they are the rules the installer always had):
 * - only addresses [DownloadPolicy] allows for the catalog's exact repo, re-checked on every redirect hop; HTTPS only;
 * - at most [maxBytes], whether the server announces a size or not;
 * - the finished file must look like an APK and must match GitHub's published SHA-256 when GitHub gives one.
 *
 * Stop, resume, cancel: [download] checks `cancelled` between reads and, when it turns true, throws
 * [ApkDownloadStopped] and **keeps** the partial file (`<package>.apk.part`). The next [download] continues after those
 * bytes with an HTTP Range request, re-hashing what is already there first so the final SHA-256 still covers the whole
 * file. A server that ignores the range restarts the file from zero; one that rejects it (416) has the partial deleted
 * and the call fails. [discardPartial] is the Cancel that throws the partial away. A finished file that is oversized,
 * not an APK or does not match its checksum is deleted, never kept for resume.
 *
 * A kept partial belongs to **one asset**: its address is recorded beside it (`<package>.apk.part.src`). A call for a different asset
 * (another release picked for the same app) deletes the partial and starts from zero, because appending one release's bytes to
 * another's would produce a corrupt APK that only a published checksum could catch. A partial with no record (written by an older
 * build) is treated the same way.
 *
 * A stop takes effect at once on a flowing connection; a stalled connection can delay it by up to the read timeout.
 */
class ApkDownloader(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRedirects: Int = 5,
    private val allowAsset: (url: String, repo: String) -> Boolean = DownloadPolicy::isAllowedAsset,
    private val allowRedirect: (url: String, repo: String) -> Boolean = DownloadPolicy::isAllowedRedirect,
    private val httpsOnly: Boolean = true,
) {
    /** Bytes a stopped or interrupted download of [packageName] left behind, or 0. */
    fun partialBytes(packageName: String): Long = partFile(packageName).let { if (it.isFile) it.length() else 0L }

    /** The asset address the kept partial of [packageName] came from, or null when there is no partial or no record of it. */
    fun partialSource(packageName: String): String? =
        if (partFile(packageName).isFile) sourceFile(packageName).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } else null

    /** Deletes what a stopped or interrupted download of [packageName] left behind. True when a file was removed. */
    fun discardPartial(packageName: String): Boolean {
        sourceFile(packageName).delete()
        return partFile(packageName).let { it.isFile && it.delete() }
    }

    /**
     * [onProgress] gets the bytes on disk so far (including a resumed prefix) and the expected total, or -1 when the
     * server did not say.
     */
    fun download(
        repo: String,
        packageName: String,
        asset: ReleaseAsset,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): DownloadedApk {
        require(allowAsset(asset.url, repo)) { "Download address is not allowed." }
        dir.mkdirs()
        val finalFile = File(dir, "$packageName.apk")
        val part = partFile(packageName)
        val source = sourceFile(packageName)
        finalFile.delete()
        // A kept prefix only continues the asset it came from. Anything else (another release, or no record) cannot be resumed.
        if (part.isFile && partialSource(packageName) != asset.url) {
            part.delete()
            source.delete()
        }
        // A kept prefix larger than the limit, or larger than the asset it should be part of, cannot belong to it.
        if (part.isFile && (part.length() > maxBytes || (asset.sizeBytes > 0 && part.length() > asset.sizeBytes))) part.delete()
        val existing = if (part.isFile) part.length() else 0L

        var connection: HttpURLConnection? = null
        try {
            var url = asset.url
            var code = 0
            for (hop in 0..maxRedirects) {
                connection?.disconnect()
                val conn = open(url).apply {
                    instanceFollowRedirects = false
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                connection = conn
                code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location") ?: error("Redirect without a target.")
                    val resolved = URL(URL(url), next).toString()
                    check(allowRedirect(resolved, repo)) { "Redirect to a host that is not allowed." }
                    url = resolved
                    continue
                }
                break
            }
            val conn = checkNotNull(connection)
            check(code !in 300..399) { "Too many redirects." }
            val resuming = when {
                code == 206 && existing > 0 && conn.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true -> true
                code == 200 -> false
                code == 416 || (code == 206 && existing > 0) -> {
                    // 416: the server rejected the range. 206 that does not continue exactly after our bytes: its data cannot be
                    // appended safely. Either way keeping the partial would fail every retry, so it is discarded.
                    part.delete()
                    throw IOException("The server did not continue the download from the right place; the partial file was discarded, try again.")
                }
                else -> error("Download failed (HTTP $code).")
            }
            val announced = conn.contentLengthLong
            val total = when {
                announced < 0 -> -1L
                resuming -> existing + announced
                else -> announced
            }
            check(total < 0 || total <= maxBytes) { "File is larger than the ${maxBytes / 1_000_000} MB limit." }

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            if (resuming) {
                // The checksum covers the whole file, so the bytes already on disk go through it first.
                part.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        received += n
                    }
                }
                onProgress(received, total)
            }
            // From here the partial is kept on a stop or a dropped connection, and judged only once the body is complete.
            source.writeText(asset.url)
            conn.inputStream.use { input ->
                FileOutputStream(part, resuming).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled()) throw ApkDownloadStopped()
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        if (received > maxBytes) {
                            part.delete()
                            error("File is larger than the limit.")
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(received, total)
                    }
                }
            }
            if (total >= 0 && received != total) throw IOException("The download ended early ($received of $total bytes). Start it again to continue.")

            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val head = ByteArray(4)
            val headRead = part.inputStream().use { it.read(head) }
            if (headRead != 4) head.fill(0)
            if (!ApkVerifier.looksLikeApk(head)) {
                part.delete()
                error("The downloaded file is not an APK.")
            }
            if (asset.sha256 != null && !ApkVerifier.matches(asset.sha256, sha)) {
                part.delete()
                error("The download does not match the SHA-256 GitHub published. Nothing was installed.")
            }
            Files.move(part.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            source.delete()
            return DownloadedApk(finalFile, sha, digestChecked = asset.sha256 != null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun partFile(packageName: String) = File(dir, "$packageName.apk.part")

    private fun sourceFile(packageName: String) = File(dir, "$packageName.apk.part.src")

    private fun open(url: String): HttpURLConnection {
        require(!httpsOnly || url.startsWith("https://")) { "HTTPS only." }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // A generic client name: nothing about the owner or this phone is sent.
            setRequestProperty("User-Agent", "fleet-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 300L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
    }
}
