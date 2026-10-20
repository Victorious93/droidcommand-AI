package ai.droidcommand.app.shizuku

import ai.droidcommand.shizuku.ShizukuManager
import ai.droidcommand.shizuku.ShizukuState
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ShizukuStatusViewModel @Inject constructor(
    private val manager: ShizukuManager,
) : ViewModel() {
    private val _state = MutableStateFlow(manager.state())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val listener: (ShizukuState) -> Unit = { _state.value = it }

    init {
        manager.addListener(listener)
    }

    fun requestPermission() = manager.requestPermission()

    override fun onCleared() {
        manager.removeListener(listener)
        super.onCleared()
    }
}
