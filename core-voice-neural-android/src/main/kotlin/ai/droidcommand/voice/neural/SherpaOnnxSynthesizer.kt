package ai.droidcommand.voice.neural

import ai.droidcommand.voice.NeuralSynthesizer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Files of one already-downloaded VITS/Piper-style voice. Downloading and SHA-256-verifying them is NOT
 * done here (see docs/VOICE_PHASE_SCOPE.md V2 "still open"); this class only reads a directory a caller
 * has already populated. [dataDir] is the espeak-ng data directory some voices need — its license is the
 * unresolved owner question in that doc, so it is optional here and nothing bundles it.
 */
data class NeuralVoiceFiles(
    val model: File,
    val tokens: File,
    val dataDir: File? = null,
    val lexicon: File? = null,
) {
    fun present(): Boolean = model.isFile && tokens.isFile && (dataDir?.isDirectory ?: true) && (lexicon?.isFile ?: true)
}

/**
 * [NeuralSynthesizer] over the prebuilt sherpa-onnx v1.13.8 Kotlin API. NEVER COMPILED OR RUN (no Android
 * SDK/device here). Written against signatures read with `javap` from that release's `classes.jar`:
 * `OfflineTts(config = ...)`, `generateWithCallback(text, sid, speed, (FloatArray) -> Int)`, `sampleRate()`,
 * `release()`, and the no-arg constructors + setters of the config classes (used instead of positional
 * constructors so field order cannot be mismatched).
 *
 * Unverified assumptions: the callback's `Int` return is 1 to continue and 0 to stop (recalled from the
 * sherpa-onnx C API docs, not read from this artifact); the `AssetManager` constructor parameter defaults to
 * null (a default-arguments overload exists, its nullability was not inspected).
 *
 * Only VITS/Piper is wired; Kokoro and the other model types in the AAR have their own config classes and
 * are deliberately not added until a single voice is proven on a device.
 */
class SherpaOnnxSynthesizer(
    private val voice: NeuralVoiceFiles,
    private val speakerId: Int = 0,
    private val speed: Float = 1f,
    private val numThreads: Int = 2,
) : NeuralSynthesizer {
    private val nativeLoadable: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private var tts: OfflineTts? = null

    override val available: Boolean get() = voice.present() && nativeLoadable

    override val sampleRate: Int get() = engine().sampleRate()

    override fun synthesize(text: String, onChunk: (FloatArray) -> Boolean) {
        engine().generateWithCallback(text, speakerId, speed) { samples -> if (onChunk(samples)) 1 else 0 }
    }

    /** Frees the native model. Call when the owning scope ends; a later [synthesize] reloads it. */
    @Synchronized
    fun release() {
        tts?.release()
        tts = null
    }

    @Synchronized
    private fun engine(): OfflineTts = tts ?: OfflineTts(config = config()).also { tts = it }

    private fun config(): OfflineTtsConfig {
        val vits = OfflineTtsVitsModelConfig().apply {
            model = voice.model.absolutePath
            tokens = voice.tokens.absolutePath
            voice.dataDir?.let { dataDir = it.absolutePath }
            voice.lexicon?.let { lexicon = it.absolutePath }
        }
        val modelConfig = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = this@SherpaOnnxSynthesizer.numThreads
            provider = "cpu"
        }
        return OfflineTtsConfig().apply { model = modelConfig }
    }
}
