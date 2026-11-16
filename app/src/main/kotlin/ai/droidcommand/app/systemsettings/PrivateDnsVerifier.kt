package ai.droidcommand.app.systemsettings

import ai.droidcommand.systemsettings.EffectVerdict
import ai.droidcommand.systemsettings.EffectVerifier
import ai.droidcommand.systemsettings.SettingKey
import ai.droidcommand.systemsettings.SettingsCatalog
import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings

/**
 * Whether Private DNS is actually in use on the active network, which is different from the stored mode. Polls for up to [waitMs] because the
 * system validates the resolver asynchronously after the setting changes. Without an active network it says the effect cannot be verified.
 */
class PrivateDnsVerifier(private val context: Context, private val waitMs: Long = 3_000) : EffectVerifier {
    data class Status(val active: Boolean, val serverName: String?, val note: String)

    override fun verify(key: SettingKey, storedValue: String?): EffectVerdict? {
        if (key.name != SettingsCatalog.PRIVATE_DNS_MODE || storedValue == null) return null
        val deadline = System.currentTimeMillis() + waitMs
        var last = status()
        while (last == null || !matches(storedValue, last)) {
            if (last == null || System.currentTimeMillis() >= deadline) break
            Thread.sleep(POLL_MS)
            last = status()
        }
        if (last == null) return EffectVerdict(false, "No active network, so the active resolver could not be checked. The setting is stored.")
        return if (matches(storedValue, last)) {
            EffectVerdict(true, "Active resolver matches: ${describe(last)}.")
        } else {
            EffectVerdict(false, "Stored, but the active network reports ${describe(last)}; it can take a moment to change or the network may not support it.")
        }
    }

    fun status(): Status? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        return Status(lp.isPrivateDnsActive, lp.privateDnsServerName, "")
    }

    private fun matches(mode: String, s: Status): Boolean = when (mode) {
        "off" -> !s.active
        // Automatic mode may legitimately fall back to plain DNS, so only an observed encrypted connection counts as the effect.
        "opportunistic" -> s.active
        "hostname" -> s.active && s.serverName != null && s.serverName == Settings.Global.getString(context.contentResolver, SettingsCatalog.PRIVATE_DNS_SPECIFIER)
        else -> false
    }

    private fun describe(s: Status) = if (s.active) "encrypted DNS in use (${s.serverName ?: "automatic"})" else "encrypted DNS not in use"

    private companion object {
        const val POLL_MS = 400L
    }
}
