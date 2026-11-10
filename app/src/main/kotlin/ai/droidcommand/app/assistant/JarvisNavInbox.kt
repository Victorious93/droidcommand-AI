package ai.droidcommand.app.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot navigation request from the hands-free voice assistant. */
object JarvisNavInbox {
    private val pending = MutableStateFlow<JarvisDestination?>(null)
    val state: StateFlow<JarvisDestination?> = pending.asStateFlow()

    fun offer(destination: JarvisDestination) {
        pending.value = destination
    }

    fun consume(): JarvisDestination? {
        val value = pending.value
        pending.value = null
        return value
    }
}
