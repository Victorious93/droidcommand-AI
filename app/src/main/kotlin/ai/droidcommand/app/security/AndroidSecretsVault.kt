package ai.droidcommand.app.security

import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.JceAesGcmCipher
import ai.droidcommand.config.SecretRecordStorage
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// UNBUILT/UNTESTED: written without an Android SDK. The Keystore key parameters and the
// commit()-for-durability storage follow OpenDroid's KeystoreSecretStorage (Apache-2.0); all
// envelope/AAD/fail-closed logic lives in the JVM-tested core-config.EncryptedSecretsVault.

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "droidcommand.secrets.v1"
private const val PREFS_NAME = "droidcommand_secret_records"

/**
 * Loads (creating on first use) a non-extractable AES-256 key in the AndroidKeyStore. No user-auth
 * requirement: background provider calls must decrypt without a foreground unlock prompt.
 */
internal fun loadOrCreateKeystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
    }.generateKey()
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
    cipher = JceAesGcmCipher(::loadOrCreateKeystoreKey),
)
