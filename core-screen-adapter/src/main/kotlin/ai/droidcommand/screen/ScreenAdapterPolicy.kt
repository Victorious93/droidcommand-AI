package ai.droidcommand.screen

/**
 * Policy for the experimental phone-to-phone screen-control adapter. Everything here defaults to OFF.
 *
 * Separate owner grants: [viewGranted] lets the adapter show the target's screen; [inputGranted] additionally lets it send touches
 * and keys. Neither implies shell, root, files or settings access, and neither can be switched on while [enabled] is false.
 * These are DroidCommand's own policy switches. They do NOT narrow what an ADB key can do on the target — see [AdbDisclosure].
 */
data class ScreenAdapterSettings(
    val enabled: Boolean = false,
    val viewGranted: Boolean = false,
    val inputGranted: Boolean = false,
) {
    /** Fixed by design in this version; there is no setting to turn them on. */
    val clipboardSync: Boolean get() = false
    val changeTargetResolution: Boolean get() = false
    val changeTargetKeepAwake: Boolean get() = false
    val grantShellRootFilesOrSettings: Boolean get() = false

    fun enableAfterOwnerConfirmedDisclosure(): ScreenAdapterSettings = copy(enabled = true)
    fun disable(): ScreenAdapterSettings = ScreenAdapterSettings() // revoking drops BOTH grants
    fun grantView(): ScreenAdapterSettings = if (enabled) copy(viewGranted = true) else this
    fun grantInput(): ScreenAdapterSettings = if (enabled && viewGranted) copy(inputGranted = true) else this
    fun revokeView(): ScreenAdapterSettings = copy(viewGranted = false, inputGranted = false) // input without view is meaningless
    fun revokeInput(): ScreenAdapterSettings = copy(inputGranted = false)
}

enum class ScreenCapability { VIEW, INPUT }

sealed class ScreenDecision {
    object Allowed : ScreenDecision()
    data class Denied(val reason: String) : ScreenDecision()
}

object ScreenAdapterPolicy {
    fun decide(s: ScreenAdapterSettings, target: PairedTarget?, cap: ScreenCapability): ScreenDecision = when {
        !s.enabled -> ScreenDecision.Denied("Screen control is switched off (experimental, off by default).")
        target == null -> ScreenDecision.Denied("No paired target phone.")
        cap == ScreenCapability.VIEW && !s.viewGranted -> ScreenDecision.Denied("Viewing the target screen has not been allowed.")
        cap == ScreenCapability.INPUT && !(s.viewGranted && s.inputGranted) -> ScreenDecision.Denied("Sending touches and keys has not been allowed.")
        else -> ScreenDecision.Allowed
    }
}

/** A target phone the owner paired. Holds only a fingerprint of the ADB key; the private key lives in the platform key store, never here. */
data class PairedTarget(val id: String, val label: String, val adbKeyFingerprintSha256: String, val pairedAtMs: Long) {
    init {
        require(Regex("^[A-Za-z0-9._:-]{1,64}$").matches(id)) { "bad target id" }
        require(Regex("^[0-9a-f]{64}$").matches(adbKeyFingerprintSha256)) { "key fingerprint must be 64 lowercase hex chars" }
    }
}

/** Where the ADB private key goes. The implementation (in :app) must be Android Keystore-wrapped storage; a plain file is not acceptable. */
interface AdbKeyStore {
    fun hasKey(targetId: String): Boolean
    fun delete(targetId: String) // called on revoke/unpair; must really remove the key material
}

object AdbDisclosure {
    /** Shown before enabling and before every pairing. Accurate about the authority an ADB key carries. */
    const val TEXT: String =
        "Pairing this phone with a target over wireless debugging gives DroidCommand an ADB key for that target. " +
            "An ADB key is much broader than \"view\" or \"touch\": it can run shell commands, install or remove apps, change settings, " +
            "read and write shared storage and record the screen. The View and Input switches in DroidCommand are DroidCommand's own " +
            "rules; they do not limit the key. Anyone who gets the key, or the DroidCommand data that protects it, gets that broader access. " +
            "Pair only phones you own, keep the target's Wireless debugging off when you are not using this, and remove the pairing when done."
}

/** What the target phone itself reported; limits are derived from this, never assumed. */
data class TargetCapabilities(val widthPx: Int, val heightPx: Int, val androidSdk: Int, val h264: Boolean, val h265: Boolean, val refreshRateHz: Int)

data class StreamRequest(val maxLongSidePx: Int, val maxFps: Int, val maxBitrateBps: Int, val preferH265: Boolean = false)
data class StreamSettings(val maxLongSidePx: Int, val maxFps: Int, val maxBitrateBps: Int, val codec: String)

object StreamLimits {
    const val HARD_MAX_FPS = 60
    const val HARD_MAX_BITRATE_BPS = 12_000_000
    const val MIN_ANDROID_SDK = 30 // wireless-debugging pairing code needs Android 11+

    sealed class Result {
        data class Ok(val s: StreamSettings) : Result()
        data class Refused(val reason: String) : Result()
    }

    /** Clamp the request to what the target reported and to hard ceilings. Never asks the target to change its own resolution. */
    fun bound(req: StreamRequest, caps: TargetCapabilities): Result {
        if (caps.androidSdk < MIN_ANDROID_SDK) return Result.Refused("The target runs Android SDK ${caps.androidSdk}; wireless-debugging pairing needs Android 11 (SDK $MIN_ANDROID_SDK) or newer.")
        if (!caps.h264 && !caps.h265) return Result.Refused("The target reported no usable video encoder.")
        if (req.maxLongSidePx < 240 || req.maxFps < 1 || req.maxBitrateBps < 100_000) return Result.Refused("Stream settings are below the usable minimum.")
        val nativeLong = maxOf(caps.widthPx, caps.heightPx)
        val codec = if (req.preferH265 && caps.h265) {
            "h265"
        } else if (caps.h264) {
            "h264"
        } else {
            "h265"
        }
        return Result.Ok(
            StreamSettings(
                minOf(req.maxLongSidePx, nativeLong), minOf(req.maxFps, HARD_MAX_FPS, caps.refreshRateHz.coerceAtLeast(1)),
                minOf(req.maxBitrateBps, HARD_MAX_BITRATE_BPS), codec,
            ),
        )
    }
}
