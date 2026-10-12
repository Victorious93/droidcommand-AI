package ai.droidcommand.app.clipboard

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.config.EncryptedSecretsVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One clipboard history for the whole app process, so public clips survive leaving the Clipboards screen.
 * They do not survive the process ending. Private clips do survive, because they are in the encrypted vault.
 */
@Singleton
class ClipboardRepository @Inject constructor(
    vault: EncryptedSecretsVault,
    advanced: AdvancedPrefs,
) {
    val history = ClipboardHistory(publicLimit = advanced::publicClipLimit, storage = VaultPrivateClipStorage(vault))
}
