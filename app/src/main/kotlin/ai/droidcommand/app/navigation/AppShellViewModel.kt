package ai.droidcommand.app.navigation

import ai.droidcommand.app.prefs.FirstRunPrefs
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val firstRun: FirstRunPrefs,
) : ViewModel() {
    private val mutableShow = MutableStateFlow(!firstRun.isComplete())
    val showFirstRun: StateFlow<Boolean> = mutableShow.asStateFlow()
    private val mutableOpenModels = MutableStateFlow(false)
    val openModelsAfterSetup: StateFlow<Boolean> = mutableOpenModels.asStateFlow()

    fun onFirstRunFinished(openModels: Boolean = false) {
        mutableShow.value = false
        mutableOpenModels.value = openModels
    }

    fun consumeOpenModels(): Boolean {
        val open = mutableOpenModels.value
        mutableOpenModels.value = false
        return open
    }

    fun replayFirstRun() {
        firstRun.reset()
        mutableShow.value = true
    }
}
