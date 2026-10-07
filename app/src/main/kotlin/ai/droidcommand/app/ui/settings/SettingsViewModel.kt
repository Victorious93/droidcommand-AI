package ai.droidcommand.app.ui.settings

import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.config.SecretStorageException
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class KeyStatus { NOT_SET, SAVED, NEEDS_REENTRY, STORAGE_ERROR }

// Compiles; untested. A saved key is never read back into the UI; the screen only
// shows its status, so the plaintext never re-enters Compose state after the user types it.
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vault: EncryptedSecretsVault,
) : ViewModel() {
    private val mutableStatus = MutableStateFlow(CloudProviderCatalog.all.associateWith { statusOf(it) })
    val status: StateFlow<Map<CloudProviderSpec, KeyStatus>> = mutableStatus.asStateFlow()

    fun save(provider: CloudProviderSpec, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        try {
            vault.putSecret(provider.secretId, trimmed)
        } catch (_: SecretStorageException) {
            mutableStatus.value = mutableStatus.value + (provider to KeyStatus.STORAGE_ERROR)
            return
        }
        refresh(provider)
    }

    fun clear(provider: CloudProviderSpec) {
        try {
            vault.revokeSecret(provider.secretId)
        } catch (_: SecretStorageException) {
            mutableStatus.value = mutableStatus.value + (provider to KeyStatus.STORAGE_ERROR)
            return
        }
        refresh(provider)
    }

    private fun refresh(provider: CloudProviderSpec) {
        mutableStatus.value = mutableStatus.value + (provider to statusOf(provider))
    }

    private fun statusOf(provider: CloudProviderSpec): KeyStatus = when (vault.inspect(provider.secretId)) {
        SecretState.Present -> KeyStatus.SAVED
        SecretState.Absent -> KeyStatus.NOT_SET
        SecretState.Unrecoverable -> KeyStatus.NEEDS_REENTRY
        SecretState.StorageUnavailable -> KeyStatus.STORAGE_ERROR
    }
}
