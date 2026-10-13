package ai.droidcommand.app.ui.settings

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.mode.EngineModeState
import ai.droidcommand.app.mode.EngineModes
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.prefs.CodingTunnelChoice
import ai.droidcommand.app.ui.chat.ToolbarToggle
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.config.SecretStorageException
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.SecretSlot
import ai.droidcommand.llm.factory.WebSearchCatalog
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
    private val advanced: AdvancedPrefs,
    private val modes: EngineModes,
) : ViewModel() {
    val modeState: StateFlow<EngineModeState> = modes.state
    private val mutableStatus = MutableStateFlow(CloudProviderCatalog.all.associateWith { statusOf(it.secretId) })
    val status: StateFlow<Map<CloudProviderSpec, KeyStatus>> = mutableStatus.asStateFlow()

    private val mutableSearchStatus = MutableStateFlow(WebSearchCatalog.all.associateWith { statusOf(it.secretId) })
    val searchStatus: StateFlow<Map<SecretSlot, KeyStatus>> = mutableSearchStatus.asStateFlow()

    private val mutableToolbar = MutableStateFlow(advanced.toolbarToggles())
    val toolbarToggles: StateFlow<Set<ToolbarToggle>> = mutableToolbar.asStateFlow()

    private val mutablePublicLimit = MutableStateFlow(advanced.publicClipLimit())
    val publicClipLimit: StateFlow<Int> = mutablePublicLimit.asStateFlow()

    private val mutableCodingTunnel = MutableStateFlow(advanced.codingTunnel())
    val codingTunnel: StateFlow<CodingTunnelChoice> = mutableCodingTunnel.asStateFlow()

    fun setToolbarToggle(toggle: ToolbarToggle, visible: Boolean) {
        advanced.setToolbarToggle(toggle, visible)
        mutableToolbar.value = advanced.toolbarToggles()
    }

    fun setPublicClipLimit(limit: Int) {
        advanced.setPublicClipLimit(limit)
        mutablePublicLimit.value = advanced.publicClipLimit()
    }

    fun setCodingTunnel(choice: CodingTunnelChoice) {
        advanced.setCodingTunnel(choice)
        mutableCodingTunnel.value = advanced.codingTunnel()
    }

    fun renameMode(mode: AgentMode, raw: String) = modes.rename(mode, raw)

    fun resetModeLabels() = modes.resetLabels()

    fun saveSearchKey(slot: SecretSlot, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        try {
            vault.putSecret(slot.secretId, trimmed)
        } catch (_: SecretStorageException) {
            mutableSearchStatus.value = mutableSearchStatus.value + (slot to KeyStatus.STORAGE_ERROR)
            return
        }
        mutableSearchStatus.value = mutableSearchStatus.value + (slot to statusOf(slot.secretId))
    }

    fun clearSearchKey(slot: SecretSlot) {
        try {
            vault.revokeSecret(slot.secretId)
        } catch (_: SecretStorageException) {
            mutableSearchStatus.value = mutableSearchStatus.value + (slot to KeyStatus.STORAGE_ERROR)
            return
        }
        mutableSearchStatus.value = mutableSearchStatus.value + (slot to statusOf(slot.secretId))
    }

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

    private fun statusOf(provider: CloudProviderSpec): KeyStatus = statusOf(provider.secretId)

    private fun statusOf(secretId: String): KeyStatus = when (vault.inspect(secretId)) {
        SecretState.Present -> KeyStatus.SAVED
        SecretState.Absent -> KeyStatus.NOT_SET
        SecretState.Unrecoverable -> KeyStatus.NEEDS_REENTRY
        SecretState.StorageUnavailable -> KeyStatus.STORAGE_ERROR
    }
}
