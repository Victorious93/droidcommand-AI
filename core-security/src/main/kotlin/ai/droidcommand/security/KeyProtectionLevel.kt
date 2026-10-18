package ai.droidcommand.security

/**
 * Where the key that encrypts runtime app secrets actually lives. This is a *report* about a key that
 * already exists, never a promise: the same code gives [STRONGBOX] on one phone and [SOFTWARE] on
 * another (emulators, older or low-end devices, some custom ROMs), and the app cannot choose.
 *
 * Fallback order when the key is created (see `app/security/AndroidSecretsVault.kt`): StrongBox if the
 * device has it, otherwise the device's trusted execution environment, otherwise a software-backed
 * AndroidKeyStore key. Every step is still a non-extractable AndroidKeyStore key. There is deliberately
 * **no** fallback to a key held in app memory, app files or plaintext: if the keystore itself is
 * unusable, saving a secret fails with an exception instead.
 *
 * Only release-signing keys are out of scope here; they stay in CI secrets (`docs/SECURITY.md`).
 */
enum class KeyProtectionLevel(val isHardwareBacked: Boolean) {
    /** Dedicated secure element (Android `SECURITY_LEVEL_STRONGBOX`). Strongest; rare. */
    STRONGBOX(true),

    /** Trusted execution environment, e.g. TrustZone (Android `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`). */
    TRUSTED_ENVIRONMENT(true),

    /** Key material is handled by ordinary OS code. Still non-extractable, but not hardware-isolated. */
    SOFTWARE(false),

    /** Could not be determined (no key yet, API refused to say, or a non-Android key). Treat as not hardware-backed. */
    UNKNOWN(false),
    ;

    companion object {
        // Mirrors android.security.keystore.KeyProperties.SECURITY_LEVEL_* so this module stays
        // Android-free and the mapping is JVM-testable. Pinned by KeyProtectionLevelTest.
        const val ANDROID_SECURITY_LEVEL_UNKNOWN = -1
        const val ANDROID_SECURITY_LEVEL_UNKNOWN_SECURE = -2
        const val ANDROID_SECURITY_LEVEL_SOFTWARE = 0
        const val ANDROID_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
        const val ANDROID_SECURITY_LEVEL_STRONGBOX = 2

        /**
         * From `KeyInfo.getSecurityLevel()` (API 31+). `UNKNOWN_SECURE` means Android says the key is in
         * secure hardware of a kind it cannot name; it is reported as [TRUSTED_ENVIRONMENT] because that
         * is the weaker of the two hardware levels, so the report never overstates.
         */
        fun fromAndroidSecurityLevel(level: Int): KeyProtectionLevel = when (level) {
            ANDROID_SECURITY_LEVEL_STRONGBOX -> STRONGBOX
            ANDROID_SECURITY_LEVEL_TRUSTED_ENVIRONMENT, ANDROID_SECURITY_LEVEL_UNKNOWN_SECURE -> TRUSTED_ENVIRONMENT
            ANDROID_SECURITY_LEVEL_SOFTWARE -> SOFTWARE
            else -> UNKNOWN
        }

        /**
         * From the pre-API-31 `KeyInfo.isInsideSecureHardware()`. It cannot tell StrongBox from TEE, so a
         * `true` is reported as the weaker [TRUSTED_ENVIRONMENT].
         */
        fun fromInsideSecureHardware(insideSecureHardware: Boolean): KeyProtectionLevel =
            if (insideSecureHardware) TRUSTED_ENVIRONMENT else SOFTWARE
    }
}
