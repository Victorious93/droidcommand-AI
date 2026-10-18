package ai.droidcommand.app.fleet

import ai.droidcommand.security.NodeSignatureDecision
import ai.droidcommand.security.NodeSignatureGuard
import ai.droidcommand.security.NodeSignatureVerifier
import ai.droidcommand.security.SigningCertObserver
import android.content.Context
import android.content.pm.PackageManager

/**
 * Thin PackageManager adapter for [NodeSignatureGuard]. SHA-256 comparison
 * stays in `core-security`; this class only returns the installed signing
 * certificate bytes (or empty if the package is absent / unreadable).
 *
 * minSdk is 28, so [PackageManager.GET_SIGNING_CERTIFICATES] is always
 * available. Duplicate of the copy in `core-companion` — that module is
 * still excluded from the default build.
 */
class AndroidSigningCertObserver(private val context: Context) : SigningCertObserver {
    override fun certificateBytes(packageName: String): List<ByteArray> {
        val pm = context.packageManager
        return try {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
            signers.map { it.toByteArray() }
        } catch (_: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }
}

/**
 * Controller-side gate used before bindService / Messenger bind / commanding
 * a node. A [NodeSignatureDecision.Refused] result must not proceed.
 *
 * Adds a check; it does not replace DependencyGuard, signature permissions,
 * or Kali/pentest authorization.
 */
class ControllerNodeSignature(
    context: Context,
    verifier: NodeSignatureVerifier = NodeSignatureVerifier(),
) {
    private val guard = NodeSignatureGuard(AndroidSigningCertObserver(context), verifier)

    fun beforeBindOrCommand(packageName: String): NodeSignatureDecision = guard.check(packageName)
}
