package ai.droidcommand.app.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Which backend runs Metasploit/SET jobs, and the non-secret half of a configured VictorLinux
 * security backend's address. The backend's own API key is a secret and lives in
 * [ai.droidcommand.config.EncryptedSecretsVault] under [SECURITY_BACKEND_API_KEY_SECRET_ID],
 * never here — the same split [ai.droidcommand.app.ui.pchost.PcHostViewModel] already uses for
 * PC-hosted model endpoints.
 *
 * **Explicit, remembered, never silently switched** (master-prompt §7's own requirement): a
 * tool run always uses whichever [SecurityBackend] is currently selected; a failure to reach
 * the Linux backend is reported as a failure, never retried on VictorSuite's Kali proot
 * instead without the owner choosing that themselves.
 */
enum class SecurityBackend { PHONE_LOCAL, LINUX_BACKEND }

const val SECURITY_BACKEND_API_KEY_SECRET_ID = "security_backend_api_key"

class SecurityBackendPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Phone-local (VictorSuite Kali via RUN_COMMAND) until the owner explicitly configures and selects a Linux backend. */
    fun selectedBackend(): SecurityBackend =
        if (prefs.getString(KEY_BACKEND, null) == SecurityBackend.LINUX_BACKEND.name) {
            SecurityBackend.LINUX_BACKEND
        } else {
            SecurityBackend.PHONE_LOCAL
        }

    fun setSelectedBackend(backend: SecurityBackend) {
        prefs.edit { putString(KEY_BACKEND, backend.name) }
    }

    /** e.g. "http://192.168.1.50:18099". Null until the owner saves one on the connection/setup screen. */
    fun linuxBackendBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    fun setLinuxBackendBaseUrl(url: String?) {
        prefs.edit { putString(KEY_BASE_URL, url?.trim()?.takeIf { it.isNotBlank() }) }
    }

    companion object {
        private const val FILE = "security_backend_prefs"
        private const val KEY_BACKEND = "selected_backend"
        private const val KEY_BASE_URL = "linux_backend_base_url"
    }
}
