package ai.droidcommand.agent

/**
 * User-facing names for the two engine modes. [mode1] is always [AgentMode.PILOT] and [mode2] is
 * always [AgentMode.FORGE]. Renaming a label does not change which engine runs.
 *
 * Labels are trimmed. A blank label, or two labels that trim to the same text, is rejected so a
 * mode switch cannot become ambiguous.
 */
data class ModeLabels(
    val mode1: String = DEFAULT_MODE_1,
    val mode2: String = DEFAULT_MODE_2,
) {
    init {
        require(mode1.isNotBlank()) { "Mode 1 label must not be blank" }
        require(mode2.isNotBlank()) { "Mode 2 label must not be blank" }
        require(mode1.trim() != mode2.trim()) { "Mode labels must be different" }
    }

    fun labelFor(mode: AgentMode): String = when (mode) {
        AgentMode.PILOT -> mode1.trim()
        AgentMode.FORGE -> mode2.trim()
    }

    /** The engine that actually runs for [mode]. Stable; not the user-facing label. */
    fun engineName(mode: AgentMode): String = when (mode) {
        AgentMode.PILOT -> "Pilot"
        AgentMode.FORGE -> "Forge"
    }

    fun rename(mode: AgentMode, label: String): ModeLabels = when (mode) {
        AgentMode.PILOT -> copy(mode1 = label.trim())
        AgentMode.FORGE -> copy(mode2 = label.trim())
    }

    companion object {
        const val DEFAULT_MODE_1 = "Mode 1"
        const val DEFAULT_MODE_2 = "Mode 2"
    }
}
