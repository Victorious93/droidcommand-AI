package ai.droidcommand.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ClipboardUiState(
    val publicClips: List<Clip> = emptyList(),
    val privateClips: List<Clip> = emptyList(),
    val privateUnreadable: Boolean = false,
    /** Outcome of the last action, shown above the lists. */
    val message: String? = null,
)

/**
 * Presents the app's clipboards for [ClipboardScreen]. The only bridge to the Android system clipboard is the explicit
 * paste and copy actions below. Android lets an app read the system clipboard only while it is in the foreground.
 */
@HiltViewModel
class ClipboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    repository: ClipboardRepository,
) : ViewModel() {
    private val history = repository.history
    private val mutableState = MutableStateFlow(snapshot(message = null))
    val state: StateFlow<ClipboardUiState> = mutableState.asStateFlow()

    private fun snapshot(message: String?) = ClipboardUiState(
        publicClips = history.publicClips(),
        privateClips = history.privateClips(),
        privateUnreadable = history.privateUnreadable,
        message = message,
    )

    private fun publish(message: String? = null) {
        mutableState.value = snapshot(message)
    }

    fun pasteFromSystem() {
        val clip = context.getSystemService<ClipboardManager>()?.primaryClip
        val text = if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            publish("Nothing text-like is on the system clipboard.")
            return
        }
        publish(if (history.addPublic(text)) "Added to public clips." else "Same as the newest public clip, so not added.")
    }

    /** Puts text on the system clipboard, where any app in the foreground can read it. */
    fun copyToSystem(text: String) {
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("DroidCommand AI clip", text))
        publish("Copied to the system clipboard. Other apps can now read it.")
    }

    fun deletePublic(id: Long) {
        history.deletePublic(id)
        publish()
    }

    fun clearPublic() {
        history.clearPublic()
        publish("Public clips cleared.")
    }

    fun moveToPrivate(id: Long) {
        val moved = history.moveToPrivate(id)
        publish(
            when {
                moved -> "Moved to private clips."
                history.privateUnreadable -> "Private clips cannot be read, so nothing was moved. Erase them to start again."
                else -> "Not moved: the private list is full (${history.privateLimit}) or could not be saved."
            },
        )
    }

    fun deletePrivate(id: Long) {
        publish(if (history.deletePrivate(id)) null else "Not deleted: the private store could not be updated.")
    }

    fun eraseAllPrivate() {
        publish(if (history.discardPrivate()) "All private clips erased." else "Not erased: the private store could not be updated.")
    }
}
