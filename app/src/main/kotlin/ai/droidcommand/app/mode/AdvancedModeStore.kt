package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.prefs.AdvancedPrefs

/** [ModePreferenceStore] backed by [AdvancedPrefs] (`advanced_prefs`). */
class AdvancedModeStore(private val prefs: AdvancedPrefs) : ModePreferenceStore {
    override fun readMode(): AgentMode = prefs.agentMode()

    override fun writeMode(mode: AgentMode) = prefs.setAgentMode(mode)

    override fun readLabel(mode: AgentMode): String? = prefs.modeLabel(mode)

    override fun writeLabel(mode: AgentMode, stored: String?) = prefs.setModeLabel(mode, stored)
}
