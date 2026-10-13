package ai.droidcommand.app.modes

import ai.droidcommand.agent.AgentMode
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two agent modes, surfaced in the product UI. [AgentMode.PILOT] is the direct, user-driven
 * conversational surface (the Chat screen); [AgentMode.FORGE] is the autonomous objective loop
 * (the Plan screen). The engine names (Pilot/Forge) never change — only the user-facing label does.
 */
enum class ProductMode(val engineMode: AgentMode, val defaultLabel: String, val engineName: String) {
    MODE_ONE(AgentMode.PILOT, "Mode 1", "Pilot"),
    MODE_TWO(AgentMode.FORGE, "Mode 2", "Forge"),
}

/** Current labels for the two modes. Defaults are "Mode 1" / "Mode 2"; the user can rename either. */
data class ModeLabels(
    val modeOne: String = ProductMode.MODE_ONE.defaultLabel,
    val modeTwo: String = ProductMode.MODE_TWO.defaultLabel,
) {
    fun labelFor(mode: ProductMode): String = when (mode) {
        ProductMode.MODE_ONE -> modeOne
        ProductMode.MODE_TWO -> modeTwo
    }
}

/**
 * App-lifetime, persisted owner of the user-editable mode labels. SharedPreferences-backed and
 * exposed as a [StateFlow] so the nav bar, screen titles and Settings all repaint the instant a
 * label is renamed. Renaming is purely cosmetic: it never touches [ProductMode.engineMode] or the
 * Pilot/Forge engine behaviour behind each screen.
 */
@Singleton
class ModeLabelStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val labels: StateFlow<ModeLabels> = mutable.asStateFlow()

    private fun load(): ModeLabels = ModeLabels(
        modeOne = normalizeModeLabel(prefs.getString(KEY_ONE, null)) ?: ProductMode.MODE_ONE.defaultLabel,
        modeTwo = normalizeModeLabel(prefs.getString(KEY_TWO, null)) ?: ProductMode.MODE_TWO.defaultLabel,
    )

    /** Sets one mode's label. A blank value restores that mode's default. Trimmed and length-capped. */
    fun rename(mode: ProductMode, label: String) {
        val cleaned = normalizeModeLabel(label)
        val key = if (mode == ProductMode.MODE_ONE) KEY_ONE else KEY_TWO
        prefs.edit {
            if (cleaned == null) remove(key) else putString(key, cleaned)
        }
        mutable.value = load()
    }

    fun resetToDefaults() {
        prefs.edit {
            remove(KEY_ONE)
            remove(KEY_TWO)
        }
        mutable.value = load()
    }

    private companion object {
        const val FILE = "mode_labels"
        const val KEY_ONE = "mode.one.label"
        const val KEY_TWO = "mode.two.label"
    }
}

/** Blank becomes null (the caller restores the default). Otherwise trimmed and capped at 24 characters. */
fun normalizeModeLabel(raw: String?): String? = raw?.trim()?.take(24)?.takeIf { it.isNotEmpty() }
