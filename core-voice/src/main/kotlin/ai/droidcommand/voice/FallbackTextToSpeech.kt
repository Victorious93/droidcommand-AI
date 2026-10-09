package ai.droidcommand.voice

/** Which engine the user asked for. NEURAL always falls back to SYSTEM rather than going silent. */
enum class TtsEngineChoice { SYSTEM, NEURAL }

/** [primary] when it is available, otherwise [fallback]; never silent because the preferred engine is missing. */
class FallbackTextToSpeech(
    private val primary: TextToSpeechEngine,
    private val fallback: TextToSpeechEngine,
) : TextToSpeechEngine {
    override val available: Boolean get() = primary.available || fallback.available

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        if (primary.available) {
            try {
                return primary.speak(text, onDone)
            } catch (_: Exception) {
                // Failed synchronously, before any audio: the fallback may still speak.
            }
        }
        fallback.speak(text, onDone)
    }

    override fun stop() {
        try {
            primary.stop()
        } finally {
            fallback.stop()
        }
    }
}

/** The engine for [choice]; [neural] is only consulted for [TtsEngineChoice.NEURAL]. */
fun selectTts(choice: TtsEngineChoice, neural: TextToSpeechEngine, system: TextToSpeechEngine): TextToSpeechEngine =
    when (choice) {
        TtsEngineChoice.SYSTEM -> system
        TtsEngineChoice.NEURAL -> FallbackTextToSpeech(neural, system)
    }
