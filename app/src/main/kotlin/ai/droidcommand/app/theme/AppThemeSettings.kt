package ai.droidcommand.app.theme

import ai.droidcommand.config.ThemeAccent
import ai.droidcommand.config.ThemeController
import ai.droidcommand.config.ThemeMode
import ai.droidcommand.config.ThemePreference
import ai.droidcommand.config.ThemePreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppThemeSettings @Inject constructor(store: ThemePreferenceStore) {
    private val controller = ThemeController(store)
    private val mutable = MutableStateFlow(controller.current())
    val state: StateFlow<ThemePreference> = mutable.asStateFlow()

    fun setMode(mode: ThemeMode) {
        mutable.value = controller.setMode(mode)
    }

    fun setAccent(accent: ThemeAccent) {
        mutable.value = controller.setAccent(accent)
    }

    fun setDynamicColor(on: Boolean) {
        mutable.value = controller.setDynamicColor(on)
    }
}
