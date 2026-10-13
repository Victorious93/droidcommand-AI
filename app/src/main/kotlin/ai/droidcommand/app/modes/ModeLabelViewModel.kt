package ai.droidcommand.app.modes

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin: all state lives in the singleton [ModeLabelStore], so every screen that observes [labels]
 * sees the same value and renaming from Settings repaints the nav bar and titles at once.
 */
@HiltViewModel
class ModeLabelViewModel @Inject constructor(
    private val store: ModeLabelStore,
) : ViewModel() {
    val labels: StateFlow<ModeLabels> = store.labels

    fun rename(mode: ProductMode, label: String) = store.rename(mode, label)

    fun resetToDefaults() = store.resetToDefaults()
}
