package ai.droidcommand.app.engine

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.ModeLabels
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists which engine mode is selected and the names the UI shows for them.
 * [AgentMode.PILOT] stays Mode 1's engine and [AgentMode.FORGE] stays Mode 2's engine.
 */
@Singleton
class EngineModeRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutableLabels = MutableStateFlow(readLabels())
    private val mutableMode = MutableStateFlow(readMode())

    val labels: StateFlow<ModeLabels> = mutableLabels.asStateFlow()
    val mode: StateFlow<AgentMode> = mutableMode.asStateFlow()

    fun select(mode: AgentMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
        mutableMode.value = mode
    }

    /** Returns an error message, or null when both labels were saved together. */
    fun replaceLabels(mode1: String, mode2: String): String? {
        val next = try {
            ModeLabels(mode1.trim(), mode2.trim())
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Those labels are not allowed."
        }
        prefs.edit {
            putString(KEY_MODE_1, next.mode1)
            putString(KEY_MODE_2, next.mode2)
        }
        mutableLabels.value = next
        return null
    }

    private fun readLabels(): ModeLabels {
        val mode1 = prefs.getString(KEY_MODE_1, null) ?: ModeLabels.DEFAULT_MODE_1
        val mode2 = prefs.getString(KEY_MODE_2, null) ?: ModeLabels.DEFAULT_MODE_2
        return runCatching { ModeLabels(mode1, mode2) }.getOrDefault(ModeLabels())
    }

    private fun readMode(): AgentMode =
        runCatching { AgentMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }.getOrDefault(AgentMode.PILOT)

    companion object {
        const val FILE = "engine_modes"
        private const val KEY_MODE = "selected"
        private const val KEY_MODE_1 = "label.mode1"
        private const val KEY_MODE_2 = "label.mode2"
    }
}
