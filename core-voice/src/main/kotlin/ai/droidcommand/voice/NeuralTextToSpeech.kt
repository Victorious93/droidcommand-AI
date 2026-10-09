package ai.droidcommand.voice

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * A neural speech model that produces PCM in chunks. The Android implementation wraps sherpa-onnx
 * (`core-voice-neural-android`); this module has no native code so the streaming/cancel logic is JVM-tested.
 */
interface NeuralSynthesizer {
    /** Cheap check (model files present, native library loadable). Must not load the model. */
    val available: Boolean

    /** Sample rate of the produced audio. May load the model, so call it from the synthesis thread. */
    val sampleRate: Int

    /**
     * Synthesizes [text], passing mono float PCM in [-1, 1] to [onChunk] as it is produced. [onChunk]
     * returns `false` to ask for cancellation; the implementation should stop promptly and return.
     * Throws on failure (model missing/corrupt, native error).
     */
    fun synthesize(text: String, onChunk: (FloatArray) -> Boolean)
}

/**
 * Where synthesized audio goes. Thread-safety contract: [abort] may be called from any thread at any time
 * (also with no session open) and must make an in-flight [write] or [finish] return `false` promptly.
 */
interface AudioSink {
    /** Starts a session at [sampleRate]. Throws if the audio output cannot be opened. */
    fun open(sampleRate: Int)

    /** Queues [samples]; returns `false` if the session was aborted. */
    fun write(samples: FloatArray): Boolean

    /** Blocks until everything written has been played and releases the session; `false` if aborted. */
    fun finish(): Boolean

    /** Drops queued audio, releases the session, and unblocks [write]/[finish]. Idempotent. */
    fun abort()
}

/**
 * [TextToSpeechEngine] that streams a [NeuralSynthesizer] into an [AudioSink] on a single worker thread.
 * [TextToSpeechEngine.speak]'s `onDone` fires exactly once per call: `true` only when all audio finished
 * playing; `false` on error, on [stop], or when replaced by a later [speak].
 *
 * If synthesis fails before any audio was produced (model corrupt, native library missing) the engine marks
 * itself unavailable, so a [FallbackTextToSpeech] sends the next utterance to the system voice. A failure
 * after audio began leaves it available: that is a one-off, not a broken model.
 */
class NeuralTextToSpeech(
    private val synthesizer: NeuralSynthesizer,
    private val sink: AudioSink,
    private val executor: ExecutorService = daemonSingleThread(),
) : TextToSpeechEngine {
    private val lock = Any()
    private var generation = 0L

    @Volatile private var broken = false

    override val available: Boolean get() = !broken && synthesizer.available

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        if (!available) return onDone(false)
        val mine = synchronized(lock) { ++generation }
        sink.abort() // unblock a previous utterance; the worker is single-threaded so ours starts after it
        try {
            executor.execute { run(mine, text, onDone) }
        } catch (_: RejectedExecutionException) {
            onDone(false)
        }
    }

    override fun stop() {
        synchronized(lock) { generation += 1 }
        sink.abort()
    }

    /** Stops speech and the worker thread. The engine cannot speak afterwards. */
    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    private fun cancelled(mine: Long) = synchronized(lock) { mine != generation }

    private fun run(mine: Long, text: String, onDone: (Boolean) -> Unit) {
        var wrote = false
        var refused = false
        var ok = false
        try {
            if (!cancelled(mine)) {
                sink.open(synthesizer.sampleRate)
                synthesizer.synthesize(text) { chunk ->
                    if (cancelled(mine)) {
                        false
                    } else {
                        wrote = true
                        sink.write(chunk).also { accepted -> if (!accepted) refused = true }
                    }
                }
                // A refused write means the utterance was cut short, so it must not be reported as completed.
                ok = !cancelled(mine) && !refused && wrote && sink.finish()
            }
        } catch (_: Exception) {
            if (!wrote && !cancelled(mine)) broken = true
        } catch (_: LinkageError) {
            // UnsatisfiedLinkError / NoClassDefFoundError: the native library is not there.
            if (!wrote && !cancelled(mine)) broken = true
        } finally {
            // Released here, not only on failure: finish() already released on success and abort is idempotent,
            // but a cancelled/failed run must not leave the audio output open.
            if (!ok) sink.abort()
        }
        onDone(ok)
    }

    companion object {
        private fun daemonSingleThread(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "neural-tts").apply { isDaemon = true }
        }
    }
}
