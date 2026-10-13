package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EngineModeState(
    val selected: AgentMode,
    val pilotLabel: String,
    val forgeLabel: String,
    val busy: Boolean,
    val switchError: String?,
) {
    fun label(mode: AgentMode): String = when (mode) {
        AgentMode.PILOT -> pilotLabel
        AgentMode.FORGE -> forgeLabel
    }
}

/**
 * Which engine the app is using, and what the user calls each one.
 *
 * [AgentMode] itself is unchanged: Pilot still runs one tool, Forge still runs an objective.
 * A switch is refused while a task is running, matching [ai.droidcommand.agent.DroidCommandSession.switchMode].
 * Each run builds its own session (Pilot) or [ai.droidcommand.agent.PlanRunner] (Forge), because a state
 * machine cannot be reused after it finishes — so this object, not a long-lived session, is what remembers
 * the choice across runs.
 */
class EngineModes(private val store: ModePreferenceStore) {
    private val lock = Any()
    private var selected: AgentMode = store.readMode()
    private var busy: Boolean = false
    private var switchError: String? = null
    private val mutable = MutableStateFlow(snapshot())
    val state: StateFlow<EngineModeState> = mutable.asStateFlow()

    /** Selects [mode] and persists it. Re-selecting the current mode succeeds even while a task is running. */
    fun select(mode: AgentMode): Boolean = synchronized(lock) {
        if (mode == selected) {
            switchError = null
            publish()
            return true
        }
        if (busy) {
            switchError = "Finish the running task before switching modes."
            publish()
            return false
        }
        selected = mode
        store.writeMode(mode)
        switchError = null
        publish()
        true
    }

    /** Stores [raw] as [mode]'s label. Blank input restores the default ("Mode 1" or "Mode 2"). */
    fun rename(mode: AgentMode, raw: String) {
        synchronized(lock) {
            store.writeLabel(mode, ModeLabels.normalize(raw))
            publish()
        }
    }

    fun resetLabels() {
        synchronized(lock) {
            AgentMode.entries.forEach { store.writeLabel(it, null) }
            publish()
        }
    }

    /**
     * Marks a task started for [mode]. Fails when another task is running or when [mode] is not the
     * selected one, and records the reason on [EngineModeState.switchError].
     */
    fun tryBegin(mode: AgentMode): Boolean = synchronized(lock) {
        if (selected != mode) {
            switchError = "This action belongs to ${label(mode)}. Select that mode first."
            publish()
            return false
        }
        if (busy) {
            switchError = "A task is already running."
            publish()
            return false
        }
        busy = true
        switchError = null
        publish()
        true
    }

    fun endTask() {
        synchronized(lock) {
            busy = false
            switchError = null
            publish()
        }
    }

    private fun label(mode: AgentMode): String = ModeLabels.display(store.readLabel(mode), mode)

    private fun snapshot(): EngineModeState = EngineModeState(
        selected = selected,
        pilotLabel = label(AgentMode.PILOT),
        forgeLabel = label(AgentMode.FORGE),
        busy = busy,
        switchError = switchError,
    )

    private fun publish() {
        mutable.value = snapshot()
    }
}
