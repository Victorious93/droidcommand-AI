package ai.droidcommand.app.ui.cloud

import ai.droidcommand.app.cloud.CloudSessionController
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CloudViewModel @Inject constructor(
    private val controller: CloudSessionController,
) : ViewModel() {
    val state = controller.state
    fun open(goal: String) = controller.open(goal)
    fun select(id: String) = controller.select(id)
    fun advance(id: String) = controller.advance(id)
    fun cancel(id: String) = controller.cancel(id)
}
