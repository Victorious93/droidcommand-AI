package ai.droidcommand.remotepc

/** What the Android adapter saw when it asked VictorSuite for PC profiles. */
sealed class PcProfileSource {
    /** No `com.termux` package on this phone. */
    data object VictorSuiteNotInstalled : PcProfileSource()

    /** VictorSuite is installed but has no `com.termux.victor.pc` provider (too old for My PC). */
    data object ProviderMissing : PcProfileSource()

    /** The authority resolved to some other package, or a signer we do not pin. Fail-closed. */
    data class UntrustedPublisher(val publisherPackage: String) : PcProfileSource()

    /** VictorSuite's pin check refused this app (`SecurityException`, §5 / §8). */
    data object PermissionDenied : PcProfileSource()

    /** Rows as column → text, plus the `consent_status` answer (null when the call failed). */
    data class Rows(val rows: List<Map<String, String?>>, val consent: String?) : PcProfileSource()

    /** Anything else went wrong while reading; [detail] is for the log only. */
    data class Failed(val detail: String) : PcProfileSource()
}

/** What the tile shows. Every message is plain words for Don, never an exception text. */
sealed class PcListState {
    data object Loading : PcListState()
    data class Unavailable(val message: String, val action: PcFixAction = PcFixAction.NONE) : PcListState()
    data class NeedsConsent(val message: String) : PcListState()
    data class Ready(val profiles: List<PcProfile>, val skippedRows: Int) : PcListState()
}

enum class PcFixAction { NONE, INSTALL_OR_UPDATE_VICTORSUITE, ASK_CONSENT }

object PcProfileLoader {
    const val NOT_INSTALLED = "VictorSuite is not on this phone. Install it and pair your PC there first."
    const val PROVIDER_MISSING = "This VictorSuite is too old for My PC. Update VictorSuite, then tap Refresh."
    const val UNTRUSTED = "The app that answered for VictorSuite is not your copy, so My PC will not read from it."
    const val PERMISSION_DENIED = "VictorSuite did not let this app see your PCs. Check My PC → Connected apps in VictorSuite."
    const val CONSENT_NEEDED = "VictorSuite needs your OK before this app can see your PCs."
    const val FAILED = "Could not read your PCs from VictorSuite. Tap Refresh to try again."
    const val NO_PCS = "No PCs are paired yet. Pair one in VictorSuite → My PC, then tap Refresh."

    fun evaluate(source: PcProfileSource): PcListState = when (source) {
        PcProfileSource.VictorSuiteNotInstalled -> PcListState.Unavailable(NOT_INSTALLED, PcFixAction.INSTALL_OR_UPDATE_VICTORSUITE)
        PcProfileSource.ProviderMissing -> PcListState.Unavailable(PROVIDER_MISSING, PcFixAction.INSTALL_OR_UPDATE_VICTORSUITE)
        is PcProfileSource.UntrustedPublisher -> PcListState.Unavailable(UNTRUSTED)
        PcProfileSource.PermissionDenied -> PcListState.Unavailable(PERMISSION_DENIED)
        is PcProfileSource.Failed -> PcListState.Unavailable(FAILED)
        is PcProfileSource.Rows -> {
            val (profiles, skipped) = PcProfileParser.parseRows(source.rows)
            // §5: a pinned caller without consent gets zero rows, never an exception.
            if (profiles.isEmpty() && source.consent == PcProfileContract.CONSENT_REQUIRED) {
                PcListState.NeedsConsent(CONSENT_NEEDED)
            } else {
                PcListState.Ready(profiles, skipped.size)
            }
        }
    }
}

/**
 * Who may answer for `com.termux.victor.pc`. Outbound read, but still fail-closed: the provider must belong to
 * `com.termux`, have exactly one signer, and that signer must be one of the pinned VictorSuite certs.
 */
object PcProviderTrust {
    fun isTrusted(publisherPackage: String, signerSha256: List<String>, pinnedSha256: Set<String>): Boolean {
        if (publisherPackage != PcProfileContract.VICTORSUITE_PACKAGE) return false
        if (signerSha256.size != 1) return false
        val pins = pinnedSha256.mapNotNull { canonical(it) }.toSet()
        if (pins.isEmpty()) return false
        return canonical(signerSha256.single()) in pins
    }

    /** Uppercase colon-separated SHA-256, or null if [raw] is not 32 bytes of hex. */
    fun canonical(raw: String): String? {
        val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.length != 64 || raw.any { !(it.isLetterOrDigit() || it == ':') }) return null
        return hex.uppercase().chunked(2).joinToString(":")
    }
}
