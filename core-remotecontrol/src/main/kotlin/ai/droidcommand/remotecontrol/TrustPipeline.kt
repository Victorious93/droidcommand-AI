package ai.droidcommand.remotecontrol

import ai.droidcommand.security.KnownNodeSigningCatalog
import ai.droidcommand.security.PasswordPairingDecision
import ai.droidcommand.security.PasswordPairingService
import ai.droidcommand.security.SigningCertSha256

/**
 * Inbound trust checks applied **in order** before policy:
 * (1) caller-id = package + cert pairing of the source node
 * (2) external-control switch (OFF by default)
 * (3) envelope / replay
 * (4) controller policy (handled by [RemoteCommandDispatcher])
 *
 * A VictorSMS whitelist + code word is **not** an input here. Those only
 * deliver the request.
 *
 * Cert pinning: catalogued expected fingerprints
 * ([KnownNodeSigningCatalog] / [ai.droidcommand.security.FleetMemberDescriptor.expectedSigningCertSha256])
 * are a fail-closed floor. A blank or mismatched source cert is rejected
 * for those packages even when the saved [DevicePairing.certSha256] is
 * empty. Genuinely-unknown packages (empty expected list) stay unpinned
 * here; [ai.droidcommand.security.MissingExpectedCertPolicy] (default
 * ALLOW) still governs [ai.droidcommand.security.NodeSignatureVerifier]
 * and is not flipped by this pipeline.
 */
object TrustPipeline {
    fun evaluate(
        source: SourceIdentity,
        config: RemoteControlConfig,
        envelope: FleetRemoteEnvelope,
        replayGuard: ReplayGuard,
        passwordPairing: PasswordPairingService? = null,
    ): RemoteDispatchResult.Rejected? {
        pairingDecision(source, config, passwordPairing)?.let { return reject(it) }
        if (!config.externalControlEnabled) {
            return reject(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED)
        }
        return when (val replay = replayGuard.check(envelope, source)) {
            is ReplayDecision.Reject -> reject(replay.category)
            ReplayDecision.Accept -> null
        }
    }

    fun pairingDecision(
        source: SourceIdentity,
        config: RemoteControlConfig,
        passwordPairing: PasswordPairingService? = null,
    ): RemoteErrorCategory? {
        val candidates = config.pairings.filter { it.sourceNodeRef == source.sourceNodeRef }
        if (candidates.isEmpty()) return RemoteErrorCategory.NOT_PAIRED
        val matched = candidates.any { pairingMatches(it, source, passwordPairing) }
        return if (matched) null else RemoteErrorCategory.UNAUTHORIZED
    }

    private fun pairingMatches(
        pairing: DevicePairing,
        source: SourceIdentity,
        passwordPairing: PasswordPairingService?,
    ): Boolean {
        // DevicePairing always carries a package. A null source package used
        // to match any pairing (fail-open); refuse instead.
        val sourcePackage = source.packageName ?: return false
        val packageOk = pairing.packageName == sourcePackage
        return packageOk && certMatches(pairing, source, passwordPairing)
    }

    private fun certMatches(
        pairing: DevicePairing,
        source: SourceIdentity,
        passwordPairing: PasswordPairingService? = null,
    ): Boolean {
        val packageName = source.packageName ?: pairing.packageName
        val catalogExpected = cataloguedExpectedCerts(packageName)
        val pairingRaw = pairing.certSha256?.takeIf { it.isNotBlank() }
        val pairingPin = pairingRaw?.let { SigningCertSha256.parse(it) }

        val expected = when {
            catalogExpected.isNotEmpty() -> catalogExpected
            pairingPin != null -> listOf(pairingPin)
            pairingRaw == null -> emptyList()
            else -> {
                val observedRaw = source.certSha256?.takeIf { it.isNotBlank() } ?: return false
                return pairingRaw.equals(observedRaw, ignoreCase = true)
            }
        }

        if (expected.isEmpty()) {
            return true
        }

        val observed = source.certSha256?.let { SigningCertSha256.parse(it) } ?: return false
        if (expected.any { it.canonical == observed.canonical }) return true
        val linked = passwordPairing?.acceptLinked(packageName, observed.canonical)
        return linked is PasswordPairingDecision.Allowed
    }

    fun reject(category: RemoteErrorCategory, reason: String = category.wire): RemoteDispatchResult.Rejected =
        RemoteDispatchResult.Rejected(reason, category)
}

/** Catalogued expected fingerprints for [packageName], or empty when `[Unknown]`. */
internal fun cataloguedExpectedCerts(packageName: String): List<SigningCertSha256> =
    KnownNodeSigningCatalog.identityFor(packageName)?.expectedCerts.orEmpty()

/**
 * First catalogued expected fingerprint for a suggested pairing, or
 * null when the package has none (UNENFORCED documented constants and
 * genuinely-unknown packages stay unpinned).
 */
internal fun suggestedCatalogPin(packageName: String): String? =
    cataloguedExpectedCerts(packageName).firstOrNull()?.canonical
