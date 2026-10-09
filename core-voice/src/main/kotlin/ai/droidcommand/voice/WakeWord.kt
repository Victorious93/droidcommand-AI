package ai.droidcommand.voice

/** Mono float PCM source (the Android implementation wraps `AudioRecord`). Frames arrive on a capture thread. */
interface AudioFrameSource {
    val available: Boolean
    val sampleRate: Int

    /** Starts capture; [onFrame] gets each frame in [-1, 1]. Throws if the microphone cannot be opened. */
    fun start(onFrame: (FloatArray) -> Unit)

    /** Stops capture and releases the microphone. Idempotent. */
    fun stop()
}

/** Keyword model seam (the Android implementation wraps sherpa-onnx `KeywordSpotter`). */
interface KeywordEngine {
    /** Feeds audio; returns the detected keyword text, or null. Retains no audio beyond the model's own window. */
    fun accept(samples: FloatArray, sampleRate: Int): String?

    fun reset()
}

interface WakeWordListener {
    /** Only a signal. A detection never carries audio and never performs an action by itself. */
    fun onDetected(keyword: String)

    fun onError(message: String)
}

interface WakeWordDetector {
    val available: Boolean

    fun start(listener: WakeWordListener)

    fun stop()
}

class NullWakeWordDetector : WakeWordDetector {
    override val available = false

    override fun start(listener: WakeWordListener) = listener.onError("Wake word is not available on this device.")

    override fun stop() = Unit
}

/**
 * Pipes [source] frames through [engine]. After a detection, further detections are suppressed for
 * [cooldownMs] so one utterance does not fire repeatedly. Audio is never stored or forwarded by this class;
 * an engine failure stops capture and is reported once via [WakeWordListener.onError].
 */
class AudioWakeWordDetector(
    private val source: AudioFrameSource,
    private val engine: KeywordEngine,
    private val cooldownMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) : WakeWordDetector {
    private val lock = Any()
    private var session = 0L
    private var running = false
    private var lastDetection = Long.MIN_VALUE

    override val available: Boolean get() = source.available

    override fun start(listener: WakeWordListener) {
        val mine: Long
        synchronized(lock) {
            if (running) return
            running = true
            session += 1
            mine = session
            lastDetection = Long.MIN_VALUE
        }
        try {
            engine.reset()
            source.start { frame -> onFrame(mine, frame, listener) }
        } catch (e: Exception) {
            synchronized(lock) { if (mine == session) running = false }
            listener.onError("Could not start listening for the wake word: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun stop() {
        val was = synchronized(lock) {
            val r = running
            running = false
            session += 1
            r
        }
        if (!was) return
        try {
            source.stop()
        } finally {
            engine.reset()
        }
    }

    private fun onFrame(mine: Long, frame: FloatArray, listener: WakeWordListener) {
        synchronized(lock) { if (mine != session || !running) return }
        val keyword = try {
            engine.accept(frame, source.sampleRate)
        } catch (e: Exception) {
            stop()
            listener.onError("Wake word detection failed: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (keyword.isNullOrBlank()) return
        val fire = synchronized(lock) {
            if (mine != session || !running) return
            val t = now()
            if (lastDetection != Long.MIN_VALUE && t - lastDetection < cooldownMs) {
                false
            } else {
                lastDetection = t
                true
            }
        }
        if (fire) listener.onDetected(keyword)
    }
}

/** Persisted user choices. Off by default; always-on listening is strictly opt-in. */
data class WakeWordSettings(
    val enabled: Boolean = false,
    /** Keep listening while the screen is off. Off by default (battery and privacy). Android service honours it. */
    val listenWhenScreenOff: Boolean = false,
)

data class WakeWordState(
    /** Whether the user has turned wake word on. */
    val enabled: Boolean = false,
    /** Whether the microphone is currently open for wake-word detection (drives the on-screen indicator). */
    val listeningForWakeWord: Boolean = false,
    val error: String? = null,
)

/**
 * Connects a [WakeWordDetector] to [VoiceController]. The only thing a detection can do is call
 * [VoiceController.startListening] — it cannot send a message, run a tool or approve anything. The detector
 * is stopped while a command is being dictated (the microphone is exclusive) and resumed when dictation ends.
 * Detections are ignored while the assistant is speaking, so its own voice cannot trigger it.
 * Forward every [VoiceState] update to [onVoiceState].
 */
class WakeWordController(
    private val detector: WakeWordDetector,
    private val voice: VoiceController,
    private val micPermitted: () -> Boolean,
    private val onTranscript: (String) -> Unit,
    private val onChange: (WakeWordState) -> Unit,
) {
    private enum class Phase { OFF, DETECTING, STARTING, LISTENING, HELD }

    private val lock = Any()
    private var phase = Phase.OFF
    private var holds = 0
    private var state = WakeWordState()

    private val listener = object : WakeWordListener {
        override fun onDetected(keyword: String) = handleDetected()

        override fun onError(message: String) {
            synchronized(lock) { phase = Phase.OFF }
            publish { it.copy(enabled = false, listeningForWakeWord = false, error = message) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            synchronized(lock) { phase = Phase.OFF }
            detector.stop()
            return publish { WakeWordState() }
        }
        if (!micPermitted()) {
            return publish { it.copy(enabled = false, error = "Microphone permission is needed for the wake word.") }
        }
        if (!detector.available) {
            return publish { it.copy(enabled = false, error = "Wake word is not available on this device.") }
        }
        publish { it.copy(enabled = true, error = null) }
        resume()
    }

    /**
     * Frees the microphone for something else (a voice-approval session, screen off) until the matching
     * [release]. Holds are counted, so independent callers do not release each other's hold.
     */
    fun hold() {
        val stop = synchronized(lock) {
            holds += 1
            if (phase == Phase.DETECTING) {
                phase = Phase.HELD
                true
            } else {
                false
            }
        }
        if (stop) {
            detector.stop()
            publish { it.copy(listeningForWakeWord = false) }
        }
    }

    fun release() {
        val go = synchronized(lock) {
            if (holds > 0) holds -= 1
            holds == 0 && phase == Phase.HELD
        }
        if (go) resume()
    }

    fun onVoiceState(s: VoiceState) {
        val resume = synchronized(lock) {
            when {
                s.listening && phase == Phase.STARTING -> {
                    phase = Phase.LISTENING
                    false
                }
                !s.listening && phase == Phase.LISTENING -> true
                else -> false
            }
        }
        if (resume) resume()
    }

    private fun handleDetected() {
        val go = synchronized(lock) {
            if (phase != Phase.DETECTING || voice.snapshot().speaking) {
                false
            } else {
                phase = Phase.STARTING
                true
            }
        }
        if (!go) return
        detector.stop()
        publish { it.copy(listeningForWakeWord = false) }
        voice.startListening(micPermitted(), onTranscript)
        // If dictation could not start (no permission, STT unavailable) no listening state will ever arrive.
        val stuck = synchronized(lock) { phase == Phase.STARTING && !voice.snapshot().listening }
        if (stuck) resume()
    }

    private fun resume() {
        val ok = synchronized(lock) {
            when {
                !state.enabled -> {
                    phase = Phase.OFF
                    false
                }
                holds > 0 -> {
                    phase = Phase.HELD
                    false
                }
                else -> {
                    phase = Phase.DETECTING
                    true
                }
            }
        }
        if (!ok) {
            publish { it.copy(listeningForWakeWord = false) }
            return
        }
        detector.start(listener)
        publish { it.copy(listeningForWakeWord = synchronized(lock) { phase == Phase.DETECTING }) }
    }

    private fun publish(change: (WakeWordState) -> WakeWordState) {
        val snapshot = synchronized(lock) { change(state).also { state = it } }
        onChange(snapshot)
    }
}
