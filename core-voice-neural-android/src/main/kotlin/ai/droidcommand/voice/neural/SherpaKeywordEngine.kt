package ai.droidcommand.voice.neural

import ai.droidcommand.voice.KeywordEngine
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Files of one streaming-transducer keyword-spotting model. [keywords] is sherpa-onnx's keywords file, whose
 * lines must already be TOKENIZED for the model (it is produced with the project's own tooling, not
 * free text), so a custom wake phrase cannot be typed in on the device — it ships with the model.
 */
data class KeywordModelFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File, val keywords: File) {
    fun present() = listOf(encoder, decoder, joiner, tokens, keywords).all { it.isFile }
}

/**
 * [KeywordEngine] over sherpa-onnx's `KeywordSpotter`. NEVER COMPILED OR RUN. Written against signatures read
 * with `javap` from the v1.13.8 `classes.jar` (the class's existence in the Android AAR answers the scope
 * doc's "is KWS supported on Android" question; that it WORKS on a device is unverified). Unverified
 * assumptions: `createStream("")` means "use the keywords file from the config"; sample rate 16 kHz and 80
 * feature dims match the chosen model (they are the usual values for the published KWS zipformer models, not
 * read from one).
 */
class SherpaKeywordEngine(
    private val files: KeywordModelFiles,
    private val threshold: Float = 0.25f,
    private val boost: Float = 1.0f,
) : KeywordEngine {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    val available: Boolean get() = files.present()

    @Synchronized
    override fun accept(samples: FloatArray, sampleRate: Int): String? {
        val sp = spotter ?: KeywordSpotter(config = config()).also { spotter = it }
        val st = stream ?: sp.createStream("").also { stream = it }
        st.acceptWaveform(samples, sampleRate)
        while (sp.isReady(st)) sp.decode(st)
        val keyword = sp.getResult(st).keyword
        if (keyword.isBlank()) return null
        sp.reset(st) // start over so the next utterance is judged fresh
        return keyword
    }

    @Synchronized
    override fun reset() {
        val sp = spotter ?: return
        stream?.let { sp.reset(it) }
    }

    /** Frees the native model. A later [accept] reloads it. */
    @Synchronized
    fun release() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
    }

    private fun config(): KeywordSpotterConfig {
        val transducer = OnlineTransducerModelConfig().apply {
            encoder = files.encoder.absolutePath
            decoder = files.decoder.absolutePath
            joiner = files.joiner.absolutePath
        }
        val model = OnlineModelConfig().apply {
            this.transducer = transducer
            tokens = files.tokens.absolutePath
            numThreads = 1
            provider = "cpu"
        }
        return KeywordSpotterConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = 16_000
                featureDim = 80
            }
            modelConfig = model
            keywordsFile = files.keywords.absolutePath
            keywordsThreshold = threshold
            keywordsScore = boost
        }
    }
}
