package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.fleet.tools.BotSmsAccess
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The owner's switch and number list for bots texting through VictorSMS. Both start empty / off. */
@HiltViewModel
class VictorSmsBotsViewModel @Inject constructor(
    private val prefs: AdvancedPrefs,
) : ViewModel() {
    var switchedOn by mutableStateOf(prefs.victorSmsBotsEnabled())
        private set
    var recipientsText by mutableStateOf(prefs.victorSmsBotRecipientsText())
        private set

    /** Lines that are not a phone number; they are kept in the box but can never match, so the owner sees them. */
    val invalidLines: List<String>
        get() = recipientsText.lines().map { it.trim() }.filter { it.isNotEmpty() && BotSmsAccess.normalize(it) == null }

    fun setEnabled(on: Boolean) {
        prefs.setVictorSmsBotsEnabled(on)
        switchedOn = on
    }

    fun setRecipients(text: String) {
        prefs.setVictorSmsBotRecipientsText(text)
        recipientsText = text
    }
}
