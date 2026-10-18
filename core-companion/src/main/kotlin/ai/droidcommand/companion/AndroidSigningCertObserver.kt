package ai.droidcommand.companion

import ai.droidcommand.security.SigningCertObserver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Thin PackageManager adapter for [ai.droidcommand.security.NodeSignatureGuard].
 * SHA-256 comparison stays in `core-security`. minSdk here is 26, so API 28+
 * uses [PackageManager.GET_SIGNING_CERTIFICATES] and older uses the
 * deprecated signatures field.
 */
class AndroidSigningCertObserver(private val context: Context) : SigningCertObserver {
    override fun certificateBytes(packageName: String): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
                signers.map { it.toByteArray() }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }
}
