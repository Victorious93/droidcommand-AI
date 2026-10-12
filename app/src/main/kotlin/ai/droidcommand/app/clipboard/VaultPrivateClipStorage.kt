package ai.droidcommand.app.clipboard

import ai.droidcommand.config.EncryptedSecretsVault

/**
 * Private clips in the app's encrypted vault. The whole list is one secret, so each save either replaces it or
 * fails as a unit. The vault encrypts with AES-GCM under the Android Keystore key set up in
 * `app/security/AndroidSecretsVault.kt`.
 */
class VaultPrivateClipStorage(private val vault: EncryptedSecretsVault) : PrivateClipStorage {
    /** Any failure to read counts as unreadable, so nothing is written over data that could not be opened. */
    override fun load(): List<String>? {
        return try {
            val raw = vault.getSecret(SECRET_ID) ?: return emptyList()
            PrivateClipCodec.decode(raw)
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun save(texts: List<String>) {
        if (texts.isEmpty()) {
            vault.revokeSecret(SECRET_ID)
        } else {
            vault.putSecret(SECRET_ID, PrivateClipCodec.encode(texts))
        }
    }

    private companion object {
        const val SECRET_ID = "clipboard.private.v1"
    }
}
