package ai.droidcommand.voice

data class VoiceState(
    val listening: Boolean = false,
    val speaking: Boolean = false,
    /** Interim transcript while [listening]; empty otherwise. */
    val partial: String = "",
    /** Last voice problem, user-presentable. Voice trouble never throws into the chat flow. */
    val error: String? = null,
)

/**
 * Owns one STT and one TTS engine and the state the chat UI shows. Every failure path (permission denied,
 * engine unavailable, engine throwing) degrades to a [VoiceState.error] message and silence — chat itself
 * is never affected. Microphone permission is the caller's to obtain; it is passed in per call because
 * this module has no Android types, and a denial is just `micPermitted = false`.
 *
 * Engine callbacks may arrive on any thread; state is updated under a lock and [onState] is called with an
 * immutable snapshot.
 */
class VoiceController(
    private val stt: SpeechToText,
    private val tts: TextToSpeechEngine,
    private val onState: (VoiceState) -> Unit,
) {
    private val lock = Any()
    private var state = VoiceState()
    private var session = 0L
    private var speech = 0L

    val sttAvailable: Boolean get() = stt.available
    val ttsAvailable: Boolean get() = tts.available

    /**
     * Starts one listening session; [onTranscript] gets the final non-blank transcript. Stops any speech
     * first so the recognizer does not hear the assistant. A second call while listening is ignored.
     */
    fun startListening(micPermitted: Boolean, onTranscript: (String) -> Unit) {
        if (!micPermitted) return fail("Microphone permission denied. Allow it in system settings to dictate.")
        if (!stt.available) return fail("Speech recognition is not available on this device.")
        val mine: Long
        synchronized(lock) {
            if (state.listening) return
            session += 1
            mine = session
        }
        stopSpeaking()
        update { it.copy(listening = true, partial = "", error = null) }
        try {
            stt.start { event -> handle(mine, event, onTranscript) }
        } catch (e: Exception) {
            update { it.copy(listening = false, error = "Could not start listening: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun stopListening() {
        try {
            stt.stop()
        } catch (_: Exception) {
        }
    }

    /** Auto-speak hook: speaks [reply] only when [mode] is [SpeakMode.AUTO]. */
    fun onReply(reply: String, mode: SpeakMode) {
        if (mode == SpeakMode.AUTO) speak(reply)
    }

    /** Speaks [reply] now (tap-to-speak, or auto). Replaces any speech in progress. */
    fun speak(reply: String) {
        if (!tts.available) return fail("Text-to-speech is not available on this device.")
        val text = SpeechText.clean(reply)
        if (text.isEmpty()) return
        stopSpeaking()
        val mine = synchronized(lock) { ++speech }
        update { it.copy(speaking = true, error = null) }
        try {
            tts.speak(text) { ok ->
                // A callback from an utterance we already replaced or stopped must not touch current state.
                if (synchronized(lock) { mine != speech }) return@speak
                update { it.copy(speaking = false, error = if (ok) it.error else "Speech failed.") }
            }
        } catch (e: Exception) {
            update { it.copy(speaking = false, error = "Could not speak: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun stopSpeaking() {
        synchronized(lock) { speech += 1 }
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        update { it.copy(speaking = false) }
    }

    fun clearError() = update { it.copy(error = null) }

    private fun handle(mine: Long, event: SttEvent, onTranscript: (String) -> Unit) {
        synchronized(lock) { if (mine != session) return } // stale event from an earlier session
        when (event) {
            is SttEvent.Partial -> update { it.copy(partial = event.text) }
            is SttEvent.Final -> {
                update { it.copy(listening = false, partial = "") }
                if (event.text.isNotBlank()) onTranscript(event.text.trim())
            }
            is SttEvent.Error -> update { it.copy(listening = false, partial = "", error = event.message) }
        }
    }

    private fun fail(message: String) = update { it.copy(error = message) }

    private fun update(change: (VoiceState) -> VoiceState) {
        val snapshot = synchronized(lock) { change(state).also { state = it } }
        onState(snapshot)
    }
}
