package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode

/** Persistence for the selected engine and its two labels. Android-free so it can be faked in tests. */
interface ModePreferenceStore {
    fun readMode(): AgentMode

    fun writeMode(mode: AgentMode)

    /** The raw stored label, or null when the user has not set one. */
    fun readLabel(mode: AgentMode): String?

    fun writeLabel(mode: AgentMode, stored: String?)
}
