package ai.droidcommand.config

import ai.droidcommand.security.PasswordPairing
import ai.droidcommand.security.PasswordPairingCodec
import ai.droidcommand.security.PasswordPairingSnapshot
import ai.droidcommand.security.PasswordPairingStore

/**
 * [PasswordPairingStore] backed by [SecretsVault]. The snapshot (hashed
 * password, TOFU links, lockouts) sits in encrypted storage. The vault
 * never logs the value.
 */
class VaultPasswordPairingStore(
    private val vault: SecretsVault,
    private val secretId: String = PasswordPairing.VAULT_SECRET_ID,
) : PasswordPairingStore {
    @Synchronized
    override fun load(): PasswordPairingSnapshot {
        val raw = vault.getSecret(secretId) ?: return PasswordPairingSnapshot()
        return PasswordPairingCodec.decode(raw) ?: PasswordPairingSnapshot()
    }

    @Synchronized
    override fun save(snapshot: PasswordPairingSnapshot) {
        vault.putSecret(secretId, PasswordPairingCodec.encode(snapshot))
    }
}
