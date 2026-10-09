package ai.droidcommand.voice

/** When replies are read aloud. System voices only — no voice cloning (Consumer Roadmap, out of scope). */
enum class SpeakMode { OFF, AUTO, TAP }

sealed class SttEvent {
    /** Interim text; may be revised by later events. */
    data class Partial(val text: String) : SttEvent()

    /** The final transcript for one listening session; the session is over after this. */
    data class Final(val text: String) : SttEvent()

    /** The session ended without a transcript. [message] is user-presentable and never fabricated text. */
    data class Error(val message: String) : SttEvent()
}

/**
 * Speech-to-text seam. The Android implementation (`core-voice-android`) wraps `SpeechRecognizer` and
 * prefers on-device recognition; this module has no Android types so the controller logic is JVM-tested.
 * Implementations deliver every session's end as exactly one [SttEvent.Final] or [SttEvent.Error].
 */
interface SpeechToText {
    val available: Boolean

    fun start(listener: (SttEvent) -> Unit)

    fun stop()
}

/** Text-to-speech seam; the Android implementation wraps `TextToSpeech` with the system's voices. */
interface TextToSpeechEngine {
    val available: Boolean

    /** [onDone] is called once with `true` on completion, `false` on error or when stopped. */
    fun speak(text: String, onDone: (Boolean) -> Unit)

    fun stop()
}

/** Fail-closed stand-ins, same honesty rule as the other `Null*` types: nothing is faked. */
class NullSpeechToText : SpeechToText {
    override val available = false

    override fun start(listener: (SttEvent) -> Unit) = listener(SttEvent.Error("Speech recognition is not available on this device."))

    override fun stop() = Unit
}

class NullTextToSpeech : TextToSpeechEngine {
    override val available = false

    override fun speak(text: String, onDone: (Boolean) -> Unit) = onDone(false)

    override fun stop() = Unit
}
