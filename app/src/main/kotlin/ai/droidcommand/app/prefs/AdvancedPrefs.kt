package ai.droidcommand.app.prefs

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.automation.ShellAllowlist
import ai.droidcommand.app.ui.chat.ToolbarLayout
import ai.droidcommand.app.ui.chat.ToolbarToggle
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Advanced settings that are not secret, kept in their own SharedPreferences file. Reads and writes are
 * synchronous on purpose: they are a few bytes and happen on user taps.
 */
class AdvancedPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun toolbarToggles(): Set<ToolbarToggle> = ToolbarLayout.parse(prefs.getString(KEY_TOOLBAR, null))

    fun setToolbarToggle(toggle: ToolbarToggle, visible: Boolean) {
        val next = toolbarToggles().let { if (visible) it + toggle else it - toggle }
        prefs.edit { putString(KEY_TOOLBAR, ToolbarLayout.serialize(next)) }
    }

    fun publicClipLimit(): Int =
        prefs.getInt(KEY_PUBLIC_LIMIT, DEFAULT_PUBLIC_LIMIT).takeIf { it in PUBLIC_LIMIT_CHOICES } ?: DEFAULT_PUBLIC_LIMIT

    fun setPublicClipLimit(limit: Int) {
        require(limit in PUBLIC_LIMIT_CHOICES) { "Unsupported public clipboard limit: $limit" }
        prefs.edit { putInt(KEY_PUBLIC_LIMIT, limit) }
    }

    /** Executables that automation keys may run. Empty until the owner allows one. */
    fun shellAllowlist(): Set<String> = ShellAllowlist.parse(prefs.getString(KEY_SHELL_ALLOWLIST, null))

    fun setShellAllowlist(entries: Set<String>) {
        prefs.edit { putString(KEY_SHELL_ALLOWLIST, ShellAllowlist.serialize(entries)) }
    }

    fun codingTunnel(): CodingTunnelChoice =
        if (prefs.getString(KEY_CODING_TUNNEL, null) == CodingTunnelChoice.PAIRED.name) {
            CodingTunnelChoice.PAIRED
        } else {
            CodingTunnelChoice.LOCAL
        }

    fun setCodingTunnel(choice: CodingTunnelChoice) {
        prefs.edit { putString(KEY_CODING_TUNNEL, choice.name) }
    }

    /** The engine the user last selected. Anything other than FORGE is Pilot, including a missing value. */
    fun agentMode(): AgentMode = if (prefs.getString(KEY_AGENT_MODE, null) == AgentMode.FORGE.name) AgentMode.FORGE else AgentMode.PILOT

    fun setAgentMode(mode: AgentMode) {
        prefs.edit { putString(KEY_AGENT_MODE, mode.name) }
    }

    fun modeLabel(mode: AgentMode): String? = prefs.getString(labelKey(mode), null)

    fun setModeLabel(mode: AgentMode, stored: String?) {
        prefs.edit {
            if (stored == null) remove(labelKey(mode)) else putString(labelKey(mode), stored)
        }
    }

    companion object {
        const val FILE = "advanced_prefs"
        private const val KEY_TOOLBAR = "chat.toolbar"
        private const val KEY_PUBLIC_LIMIT = "clipboard.public.limit"
        private const val KEY_SHELL_ALLOWLIST = "automation.shell.allowlist"
        private const val KEY_CODING_TUNNEL = "coding.tunnel"
        private const val KEY_AGENT_MODE = "engine.mode"

        private fun labelKey(mode: AgentMode) = "engine.label.${mode.name}"
        val PUBLIC_LIMIT_CHOICES = listOf(10, 25, 50)
        const val DEFAULT_PUBLIC_LIMIT = 25
    }
}

/** Where a coding session runs. [PAIRED] is stored only; the app does not open a paired socket. */
enum class CodingTunnelChoice { LOCAL, PAIRED }
