package ai.droidcommand.app.update

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Where Don can get a newer debug APK.
 *
 * `https://github.com/Victorious93/droidcommand-AI/releases/latest` exists, but
 * GitHub's "Latest" badge is the older non-prerelease (`v0.1.0-phase0`). CI
 * publishes newer debug APKs as pre-releases (`gh release create --prerelease`
 * in `android-apk.yml`), which show at the top of the releases list. Regular
 * push/PR builds only upload a 7-day Actions artifact, not a stable page.
 */
object ApkDownload {
    const val PAGE_URL = "https://github.com/Victorious93/droidcommand-AI/releases"
    const val CLIP_LABEL = "APK download page"

    fun viewIntent(url: String = PAGE_URL): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    fun clipData(url: String = PAGE_URL): ClipData = ClipData.newPlainText(CLIP_LABEL, url)

    fun versionLabel(versionName: String?): String {
        val name = versionName?.trim().orEmpty().ifBlank { "unknown" }
        return "This app: $name"
    }

    fun openPage(context: Context, url: String = PAGE_URL): Boolean {
        val intent = viewIntent(url)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun copyLink(context: Context, url: String = PAGE_URL) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clipData(url))
    }
}
