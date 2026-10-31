package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.shizuku.EmbeddedShizukuCopy
import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Official Shizuku v13.6.0 APK (Apache-2.0) shipped as an asset. Runtime
 * extraction copies the APK and the matching `libshizuku.so` starter into
 * app-private storage. No ADB keys live here.
 */
class EmbeddedShizukuPayload(private val context: Context) {
    fun isAvailable(): Boolean = assetPresent() || extractedApk().isFile

    fun ensureExtracted(): ExtractedPayload {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val apk = extractedApk()
        if (!apk.isFile || apk.length() == 0L) {
            context.assets.open(ASSET_APK).use { input ->
                apk.outputStream().use { input.copyTo(it) }
            }
        }
        val packaged = File(context.applicationInfo.nativeLibraryDir, STARTER_NAME)
        val starter = if (packaged.isFile) {
            packaged
        } else {
            val extracted = File(dir, STARTER_NAME)
            extractStarter(apk, extracted)
            if (!extracted.setExecutable(true, true)) {
                throw IllegalStateException("Could not mark the Shizuku starter executable")
            }
            extracted
        }
        return ExtractedPayload(starter = starter, serverApk = apk)
    }

    private fun assetPresent(): Boolean = try {
        context.assets.open(ASSET_APK).use { true }
    } catch (_: Throwable) {
        false
    }

    private fun extractedApk(): File = File(File(context.filesDir, DIR), APK_NAME)

    private fun extractStarter(apk: File, dest: File) {
        ZipFile(apk).use { zip ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
                zip.getEntry("lib/$candidate/$STARTER_NAME") != null
            } ?: throw IllegalStateException("The embedded Shizuku APK has no $STARTER_NAME for ${Build.SUPPORTED_ABIS.joinToString()}")
            val entry = zip.getEntry("lib/$abi/$STARTER_NAME")
            zip.getInputStream(entry).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }

    data class ExtractedPayload(
        val starter: File,
        val serverApk: File,
    ) {
        val command: String = EmbeddedShizukuCopy.starterCommand(starter.absolutePath, serverApk.absolutePath)
    }

    companion object {
        const val ASSET_APK = "shizuku/shizuku-server.apk"
        const val DIR = "shizuku"
        const val APK_NAME = "shizuku-server.apk"
        const val STARTER_NAME = "libshizuku.so"
        const val KEY_PRIVATE = "adbkey"
        const val KEY_PUBLIC = "adbkey.pub"
    }
}
