package ai.droidcommand.app.fleet.install

import ai.droidcommand.fleet.update.ApkVerifier
import ai.droidcommand.fleet.update.DownloadPolicy
import ai.droidcommand.fleet.update.FleetAppSource
import ai.droidcommand.fleet.update.GitHubReleases
import ai.droidcommand.fleet.update.ReleaseAsset
import ai.droidcommand.fleet.update.UpdateDecision
import ai.droidcommand.fleet.update.UpdatePlanner
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A downloaded, verified APK on disk. [sha256] is what was actually received. */
data class DownloadedApk(val file: File, val sha256: String, val digestChecked: Boolean)

/**
 * Looks up, downloads and installs fleet app APKs. Blocking: call off the main thread.
 *
 * Safety rules, all enforced here rather than left to the caller:
 * - only GitHub release URLs of the exact repo in [FleetAppSource] are fetched, on every redirect hop
 *   ([DownloadPolicy]); HTTPS only;
 * - the file must look like an APK, and must match GitHub's published SHA-256 when GitHub gives one;
 * - installation goes through Android's PackageInstaller, so Android itself refuses an update signed by a
 *   different key than the installed app. This class never trusts a downloaded app: pairing is a separate,
 *   explicit owner step.
 *
 * Not run on a device: it compiles in CI only.
 */
class FleetAppInstaller(private val context: Context) {
    fun installedVersion(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrNull()

    fun isInstalled(packageName: String): Boolean = installedVersion(packageName) != null

    /** Looks up the newest published release for [source] and decides what to do. */
    fun check(source: FleetAppSource): UpdateDecision {
        val body = runCatching { httpGet(GitHubReleases.releasesUrl(source.repo)) }.getOrElse {
            return UpdateDecision.NothingPublished("Could not reach GitHub (${it.javaClass.simpleName}).")
        }
        val version = installedVersion(source.packageName)
        return UpdatePlanner.plan(
            source = source,
            installedVersionName = version,
            installed = version != null,
            releases = GitHubReleases.parse(body),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    fun download(source: FleetAppSource, asset: ReleaseAsset, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadedApk {
        require(DownloadPolicy.isAllowedAsset(asset.url, source.repo)) { "Download address is not allowed." }
        val dir = File(context.cacheDir, "fleet-apk").apply { mkdirs() }
        val target = File(dir, "${source.packageName}.apk")
        target.delete()
        var url = asset.url
        var connection: HttpURLConnection? = null
        try {
            for (hop in 0..MAX_REDIRECTS) {
                connection?.disconnect()
                connection = open(url).apply { instanceFollowRedirects = false }
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: error("Redirect without a target.")
                    val resolved = URL(URL(url), next).toString()
                    check(DownloadPolicy.isAllowedRedirect(resolved, source.repo)) { "Redirect to a host that is not allowed." }
                    url = resolved
                    continue
                }
                check(code == 200) { "Download failed (HTTP $code)." }
                break
            }
            val conn = checkNotNull(connection)
            check(conn.responseCode == 200) { "Too many redirects." }
            val total = conn.contentLengthLong
            check(total < 0 || total <= MAX_APK_BYTES) { "File is larger than the ${MAX_APK_BYTES / 1_000_000} MB limit." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        check(received <= MAX_APK_BYTES) { "File is larger than the limit." }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(received, total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val head = ByteArray(4)
            val headRead = target.inputStream().use { it.read(head) }
            if (headRead != 4) head.fill(0)
            if (!ApkVerifier.looksLikeApk(head)) {
                target.delete()
                error("The downloaded file is not an APK.")
            }
            if (asset.sha256 != null && !ApkVerifier.matches(asset.sha256, sha)) {
                target.delete()
                error("The download does not match the SHA-256 GitHub published. Nothing was installed.")
            }
            return DownloadedApk(target, sha, digestChecked = asset.sha256 != null)
        } finally {
            connection?.disconnect()
        }
    }

    /** False means the owner must first allow this app to install apps (Settings opens from [unknownSourcesIntent]). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hands [apk] to Android's installer. On Android 12+ an update of an app this app installed can be silent
     * ([silentIfAllowed]); a first install, or any case Android does not allow silently, shows Android's own
     * confirm screen through [FleetInstallReceiver]. Result arrives there, not here.
     */
    fun install(source: FleetAppSource, apk: DownloadedApk, silentIfAllowed: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(source.packageName)
            if (silentIfAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.file.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, FleetInstallReceiver::class.java)
                .putExtra(FleetInstallReceiver.EXTRA_FLEET_ID, source.fleetId)
                .putExtra(FleetInstallReceiver.EXTRA_NAME, source.displayName)
            // Mutable: the installer adds its status extras to this intent.
            val pending = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun httpGet(url: String): String = open(url).run {
        try {
            check(responseCode == 200) { "HTTP $responseCode" }
            inputStream.bufferedReader().use { it.readText().take(MAX_JSON_CHARS) }
        } finally {
            disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "HTTPS only." }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // A generic client name: nothing about the owner or this phone is sent.
            setRequestProperty("User-Agent", "fleet-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        const val MAX_JSON_CHARS = 2_000_000
    }
}
