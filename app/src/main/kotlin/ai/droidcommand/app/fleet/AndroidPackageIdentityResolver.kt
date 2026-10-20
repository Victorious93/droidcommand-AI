package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.PackageIdentity
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * `PackageManager` view of an installed package for the Messenger trust check: its UID and the SHA-256 of each
 * *current* APK-contents signer (`apkContentsSigners`; the rotation lineage is deliberately not consulted, so a
 * retired key never counts). minSdk is 28, so `GET_SIGNING_CERTIFICATES` is always available.
 *
 * Returns null when the package is not installed or not visible: on Android 11+ the package must be named in
 * the manifest `<queries>`, otherwise it is indistinguishable from "not installed" and the node is refused.
 */
class AndroidPackageIdentityResolver(private val context: Context) : PackageIdentityResolver {
    override fun resolve(packageName: String): PackageIdentity? {
        val info = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        val digests = signers.map { sha256Hex(it.toByteArray()) }.toSet()
        val uid = info.applicationInfo?.uid ?: return null
        return PackageIdentity(uid, digests)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
