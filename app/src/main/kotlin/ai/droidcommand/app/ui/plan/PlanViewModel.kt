package ai.droidcommand.app.ui.plan

import ai.droidcommand.app.plan.PlanController
import ai.droidcommand.app.plan.PlanUiState
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin: runs live in [PlanController] (app lifetime). The provider and model are whatever the Chat
 * screen last selected — the same `chat_prefs` entries `ChatViewModel` writes — so there is one place
 * to choose them. Those two key names are duplicated from `ChatViewModel`'s private constants.
 */
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val controller: PlanController,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val state: StateFlow<PlanUiState> = controller.state

    private fun spec(): CloudProviderSpec =
        CloudProviderCatalog.byId(prefs.getString("provider", null).orEmpty()) ?: CloudProviderCatalog.all.first()

    private fun model(spec: CloudProviderSpec): String = prefs.getString("model.${spec.id}", null) ?: spec.defaultModel

    /** "Provider · model" as it will be used if a run starts now. */
    fun targetLabel(): String = spec().let { "${it.label} · ${model(it)}" }

    fun run(objective: String) {
        val spec = spec()
        controller.start(spec, model(spec), objective)
    }

    fun stop() = controller.stop()

    fun delete(id: String) = controller.delete(id)
}
