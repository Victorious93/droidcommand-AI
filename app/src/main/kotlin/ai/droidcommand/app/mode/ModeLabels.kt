package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode

/**
 * User-facing names for the two engines. [AgentMode.PILOT] defaults to "Mode 1" and
 * [AgentMode.FORGE] defaults to "Mode 2". The stored value is a label only — it is never
 * passed into [ai.droidcommand.agent.DroidCommandSession].
 */
object ModeLabels {
    const val MAX_LENGTH = 32
    const val PILOT_DEFAULT = "Mode 1"
    const val FORGE_DEFAULT = "Mode 2"

    fun defaultFor(mode: AgentMode): String = when (mode) {
        AgentMode.PILOT -> PILOT_DEFAULT
        AgentMode.FORGE -> FORGE_DEFAULT
    }

    /**
     * Blank input returns null, meaning "use the default". Anything else is trimmed, has its
     * internal whitespace collapsed, has control characters removed, and is capped at [MAX_LENGTH].
     */
    fun normalize(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return null
        val cleaned = collapsed.filterNot { it.isISOControl() }
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_LENGTH)
    }

    fun display(stored: String?, mode: AgentMode): String = stored?.let(::normalize) ?: defaultFor(mode)
}
