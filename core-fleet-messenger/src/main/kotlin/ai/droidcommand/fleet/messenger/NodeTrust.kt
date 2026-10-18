package ai.droidcommand.fleet.messenger

/** What the OS reports about an installed package: its UID and the SHA-256 of each current signing certificate. */
data class PackageIdentity(val uid: Int, val signingCertSha256: Set<String>)

/** Looks up an installed package. `:app` implements this with `PackageManager`; tests use a fake. */
fun interface PackageIdentityResolver {
    /** Null when the package is not installed (or not visible to this app — both are refused). */
    fun resolve(packageName: String): PackageIdentity?
}

sealed class TrustDecision {
    data class Trusted(val identity: PackageIdentity) : TrustDecision()

    data class Refused(val reason: String) : TrustDecision()
}

/**
 * The controller-side safety check, run on every connect before any byte is sent: the installed
 * package must exist and be signed by exactly the paired certificate. Strict on purpose:
 *  - not installed / not visible  -> refused
 *  - signer set is not exactly `{paired cert}` -> refused (so an extra or different signer, or a
 *    multi-signer APK where the paired cert is only one of several, is never accepted)
 */
class NodeTrustVerifier(private val resolver: PackageIdentityResolver) {
    fun verify(node: PairedNode): TrustDecision {
        val identity = runCatching { resolver.resolve(node.packageName) }
            .getOrElse { return TrustDecision.Refused("cannot inspect ${node.packageName}: ${it.message ?: it::class.simpleName}") }
            ?: return TrustDecision.Refused("${node.packageName} is not installed (or not visible to this app)")
        val signers = identity.signingCertSha256.map { PairedNode.normalizeCertSha256(it) }.toSet()
        return when {
            signers.isEmpty() -> TrustDecision.Refused("${node.packageName} reports no signing certificate")
            signers.size > 1 -> TrustDecision.Refused("${node.packageName} has multiple signers; only a single paired signer is accepted")
            signers.single() != node.expectedCertSha256 ->
                TrustDecision.Refused("${node.packageName} is signed by ${signers.single()}, not the paired certificate ${node.expectedCertSha256}")
            else -> TrustDecision.Trusted(identity)
        }
    }
}
