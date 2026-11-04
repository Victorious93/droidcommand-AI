package ai.droidcommand.app.ui.settings

import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.security.PasswordPairing
import ai.droidcommand.security.PasswordPairingDecision
import ai.droidcommand.security.PasswordPairingLink
import ai.droidcommand.security.PasswordPairingService
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class PasswordPairingUiState(
    val enabled: Boolean = false,
    val hasPassword: Boolean = false,
    val links: List<PasswordPairingLink> = emptyList(),
    val message: String? = null,
)

/**
 * Settings surface for password pairing. OFF by default. The typed
 * password is hashed in the vault and is never read back into Compose
 * after Save. Compiles; never run on a device here.
 */
@HiltViewModel
class PasswordPairingViewModel @Inject constructor(
    private val pairing: PasswordPairingService,
    private val resolver: PackageIdentityResolver,
) : ViewModel() {
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<PasswordPairingUiState> = mutableState.asStateFlow()

    fun setEnabled(on: Boolean) {
        pairing.enable(on)
        publish(if (on) "Password pairing is ON." else "Password pairing is OFF.")
    }

    fun setPassword(password: String) {
        val decision = pairing.setPassword(password)
        publish(
            when (decision) {
                is PasswordPairingDecision.Allowed -> decision.message
                is PasswordPairingDecision.Refused -> decision.reason
            },
        )
    }

    fun clearPassword() {
        pairing.clearPassword()
        publish("Pairing password cleared. Links revoked.")
    }

    fun revoke(packageName: String) {
        val removed = pairing.revoke(packageName)
        publish(if (removed) "Revoked $packageName" else "$packageName was not linked")
    }

    fun linkInstalled(packageName: String, password: String) {
        if (PasswordPairing.isExcluded(packageName)) {
            publish("Excluded apps can never be linked.")
            return
        }
        val identity = resolver.resolve(packageName)
        val cert = identity?.signingCertSha256?.singleOrNull()
        if (cert == null) {
            publish("$packageName is not installed, not visible, or has multiple signers.")
            return
        }
        val decision = pairing.confirmWithPassword(
            packageName,
            cert,
            password,
            screenConfirmed = true,
        )
        publish(
            when (decision) {
                is PasswordPairingDecision.Allowed -> decision.message
                is PasswordPairingDecision.Refused -> decision.reason
            },
        )
    }

    private fun publish(message: String) {
        mutableState.update { read().copy(message = message) }
    }

    private fun read() = PasswordPairingUiState(
        enabled = pairing.isEnabled(),
        hasPassword = pairing.hasPassword(),
        links = pairing.linkedPackages(),
    )
}
