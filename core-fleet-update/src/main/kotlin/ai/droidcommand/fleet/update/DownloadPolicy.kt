package ai.droidcommand.fleet.update

import java.net.URI
import java.security.MessageDigest

/**
 * Where an APK may be downloaded from. Only HTTPS, only GitHub's release hosts, and the path must belong to
 * the exact `owner/name` repo the fleet catalog names, so a release cannot point the installer at another
 * account's file. Redirects must be re-checked with the same rule by the caller.
 */
object DownloadPolicy {
    private val HOSTS = setOf("github.com")
    private val CDN_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")

    /** [repo] is `owner/name`. Compares case-insensitively, as GitHub does. */
    fun isAllowedAsset(url: String, repo: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port != -1) return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in HOSTS) return false
        val prefix = "/${repo.lowercase()}/releases/download/"
        return uri.path?.lowercase()?.startsWith(prefix) == true && !uri.path.contains("..")
    }

    /** A redirect target after the first hop: the repo's own path on github.com, or GitHub's release CDN. */
    fun isAllowedRedirect(url: String, repo: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return host in CDN_HOSTS || isAllowedAsset(url, repo)
    }
}

object ApkVerifier {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** True only when [expected] is a well-formed digest equal to [actual]; a missing digest is never a match. */
    fun matches(expected: String?, actual: String): Boolean =
        expected != null && expected.length == 64 && expected.equals(actual, ignoreCase = true)

    /** Every APK starts with the zip signature; this rejects an HTML error page saved as `.apk`. */
    fun looksLikeApk(firstBytes: ByteArray): Boolean =
        firstBytes.size >= 4 && firstBytes[0] == 0x50.toByte() && firstBytes[1] == 0x4B.toByte() &&
            firstBytes[2] == 0x03.toByte() && firstBytes[3] == 0x04.toByte()
}
