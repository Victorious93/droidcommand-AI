package ai.droidcommand.app.security

import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.JceAesGcmCipher
import ai.droidcommand.config.SecretRecordStorage
import ai.droidcommand.security.KeyProtectionLevel
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

// Never run on a device. The Keystore key parameters and the commit()-for-durability storage follow
// OpenDroid's KeystoreSecretStorage (Victorious93/opendroid, Apache License 2.0,
// https://www.apache.org/licenses/LICENSE-2.0); the ProviderException-means-unrecoverable handling
// mirrors that file's SecretKeyUnavailableException. All envelope/AAD/fail-closed logic lives in the
// JVM-tested core-config.EncryptedSecretsVault; the StrongBox-then-default creation order and the
// KeyInfo protection report are the only Android-specific additions here.
//
// Hardware-backed strength depends on the device. Emulators and some devices give a software-backed
// AndroidKeyStore key; see KeyProtectionLevel for what each outcome means. Release-signing keys are
// unrelated and stay in CI secrets.

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "droidcommand.secrets.v1"
private const val PREFS_NAME = "droidcommand_secret_records"

/**
 * Loads (creating on first use) a non-extractable AES-256 key in the AndroidKeyStore. No user-auth
 * requirement: background provider calls must decrypt without a foreground unlock prompt.
 *
 * Creation order is StrongBox, then the platform default (a trusted execution environment where the
 * device has one, otherwise a software-backed AndroidKeyStore key). The attempt falls through on any
 * [ProviderException], which is what `StrongBoxUnavailableException` extends and what some devices throw
 * instead. A key that already exists is reused as-is and never regenerated to "upgrade" it, because that
 * would orphan every stored record; [keystoreKeyProtection] reports what it actually is. There is no
 * fallback outside the AndroidKeyStore.
 */
internal fun loadOrCreateKeystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return try {
        generateKey(strongBox = true)
    } catch (_: ProviderException) {
        generateKey(strongBox = false)
    }
}

private fun generateKey(strongBox: Boolean): SecretKey =
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
    }.generateKey()

/**
 * Asks the keystore where the current key really lives (`KeyInfo`). Reports
 * [KeyProtectionLevel.UNKNOWN] if the key cannot be loaded or inspected; never throws and never touches
 * a secret. Android 12+ can tell StrongBox from TEE; older releases only say "secure hardware or not".
 */
internal fun keystoreKeyProtection(): KeyProtectionLevel = try {
    val key = loadOrCreateKeystoreKey()
    val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        KeyProtectionLevel.fromAndroidSecurityLevel(info.securityLevel)
    } else {
        @Suppress("DEPRECATION")
        KeyProtectionLevel.fromInsideSecureHardware(info.isInsideSecureHardware)
    }
} catch (_: GeneralSecurityException) {
    KeyProtectionLevel.UNKNOWN
} catch (_: ProviderException) {
    KeyProtectionLevel.UNKNOWN
}

/** Ciphertext-only records in private SharedPreferences. `commit()` (not `apply()`) so a `true` return means durable. */
internal class SharedPreferencesSecretRecordStorage(private val prefs: SharedPreferences) : SecretRecordStorage {
    override fun read(key: String): String? = prefs.getString(key, null)

    @Suppress("UseKtx")
    override fun write(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()

    @Suppress("UseKtx")
    override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()

    override fun keys(): Set<String> = prefs.all.keys
}

fun createAndroidSecretsVault(context: Context): EncryptedSecretsVault = EncryptedSecretsVault(
    storage = SharedPreferencesSecretRecordStorage(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    ),
    cipher = JceAesGcmCipher(::loadOrCreateKeystoreKey, ::keystoreKeyProtection),
)
