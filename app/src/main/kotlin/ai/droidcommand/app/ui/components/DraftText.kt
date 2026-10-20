package ai.droidcommand.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The longest draft kept across an Activity recreation (rotation, folding or unfolding, a dark-mode or font-size change).
 * Saved state travels in a Binder transaction whose roughly 1 MB budget is shared by the whole process, so a huge paste
 * must not be saved whole: it would crash the app when it goes to the background. Longer text is cut in the saved copy only.
 */
const val MAX_SAVED_DRAFT_CHARS = 50_000

/** Saves a draft cut to [MAX_SAVED_DRAFT_CHARS]; restores it unchanged. */
internal val BoundedTextSaver: Saver<String, String> = Saver(save = { it.take(MAX_SAVED_DRAFT_CHARS) }, restore = { it })

/**
 * Text a user has typed and not yet sent or saved, kept across Activity recreation. With plain `remember` the text is gone
 * after a rotation while the ViewModel survives, so a half-typed message or note title silently vanished.
 *
 * Do not use it for secrets such as API keys: they must not be written into the saved-instance Bundle (see
 * `SettingsScreen.ApiKeyRow`).
 */
@Composable
fun rememberDraft(initial: String = ""): MutableState<String> =
    rememberSaveable(stateSaver = BoundedTextSaver) { mutableStateOf(initial) }
