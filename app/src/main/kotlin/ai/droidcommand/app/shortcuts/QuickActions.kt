package ai.droidcommand.app.shortcuts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Launcher shortcuts (long-press the app icon) and the home-screen widget.
 * Each one only opens a screen; none of them sends, runs, or deletes anything.
 */
enum class QuickAction(val intentAction: String, val shortcutId: String, val label: String) {
    CHAT("ai.droidcommand.app.action.OPEN_CHAT", "chat", "Chat"),
    TOOLBOX("ai.droidcommand.app.action.OPEN_TOOLBOX", "toolbox", "Toolbox"),
    NOTES("ai.droidcommand.app.action.OPEN_NOTES", "notes", "Notes"),
    ;

    companion object {
        fun fromIntentAction(action: String?): QuickAction? = entries.firstOrNull { it.intentAction == action }
    }
}

/** Hands a quick action from MainActivity (cold start or onNewIntent) to the navigation host, once. */
object QuickActionInbox {
    private val pending = MutableStateFlow<QuickAction?>(null)
    val state: StateFlow<QuickAction?> = pending.asStateFlow()

    fun offer(action: QuickAction?) {
        if (action != null) pending.value = action
    }

    fun consume(): QuickAction? {
        val action = pending.value
        pending.value = null
        return action
    }
}
