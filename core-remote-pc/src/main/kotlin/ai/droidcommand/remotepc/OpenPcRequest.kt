package ai.droidcommand.remotepc

/** §7.1 modes. `mirror` is reserved by the spec and deliberately absent from v1. */
enum class PcMode(val wire: String) {
    TERMINAL("terminal"),
    SFTP("sftp"),
}

/**
 * Platform-free description of the `OPEN_PC` intent. The app turns it into an explicit Intent with
 * `setPackage(com.termux)` and starts it **for result**, so VictorSuite can read the calling package (§7.1).
 */
data class OpenPcRequest(
    val action: String,
    val targetPackage: String,
    val extras: Map<String, String>,
) {
    companion object {
        // Same rule as PcProfileParser (the spec example id has a 9, outside strict base32).
        private val PROFILE_ID = Regex("^[A-Z0-9]{12}$")

        fun build(profileId: String, mode: PcMode, path: String? = null): OpenPcRequest {
            require(PROFILE_ID.matches(profileId)) { "profileId must be 12 uppercase letters or digits" }
            val extras = linkedMapOf(
                PcProfileContract.EXTRA_PROFILE_ID to profileId,
                PcProfileContract.EXTRA_MODE to mode.wire,
            )
            if (mode == PcMode.SFTP && path != null) {
                require(path.startsWith("/") && path.none { it.isISOControl() }) { "path must be an absolute remote path" }
                extras[PcProfileContract.EXTRA_PATH] = path
            }
            return OpenPcRequest(PcProfileContract.ACTION_OPEN_PC, PcProfileContract.VICTORSUITE_PACKAGE, extras)
        }

        /** §7.2 consent screen, also started for result. No extras. */
        fun consent(): OpenPcRequest =
            OpenPcRequest(PcProfileContract.ACTION_PC_CONSENT, PcProfileContract.VICTORSUITE_PACKAGE, emptyMap())
    }
}

/** Plain-words text for an `OPEN_PC` result (§7.1). Null means nothing to show. */
object OpenPcResultCopy {
    fun message(ok: Boolean, error: String?, pcName: String): String? {
        if (ok) return null
        return when (error) {
            null, "user_cancelled" -> null
            "consent_required" -> "VictorSuite needs your OK first. Tap \"Allow in VictorSuite\"."
            "not_allowed" -> "VictorSuite did not let this app open $pcName."
            "unknown_profile" -> "VictorSuite no longer has $pcName. Tap Refresh."
            "unsupported_mode" -> "This VictorSuite cannot open that yet. Update VictorSuite."
            "unreachable" -> "$pcName did not answer. Check it is on and on the same Wi-Fi or Tailscale."
            "host_key_mismatch" -> "$pcName looks different from when you paired it. VictorSuite stopped for safety. Pair it again in VictorSuite."
            "bad_request" -> "VictorSuite could not read the request. Update both apps."
            else -> "VictorSuite could not open $pcName."
        }
    }

    const val NOT_INSTALLED = "VictorSuite is not installed, or it is too old to open a PC."
}
