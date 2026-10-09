package ai.droidcommand.app.voice

import ai.droidcommand.voice.AudioWakeWordDetector
import ai.droidcommand.voice.HttpsFileDownloader
import ai.droidcommand.voice.TextToSpeechEngine
import ai.droidcommand.voice.VoiceFeatures
import ai.droidcommand.voice.VoiceModelCatalog
import ai.droidcommand.voice.VoiceModelRepository
import ai.droidcommand.voice.VoiceModelStatus
import ai.droidcommand.voice.WakeWordDetector
import ai.droidcommand.voice.WakeWordListener
import ai.droidcommand.voice.neural.AudioRecordSource
import ai.droidcommand.voice.neural.AudioTrackSink
import ai.droidcommand.voice.neural.KeywordModelFiles
import ai.droidcommand.voice.neural.NeuralVoiceFiles
import ai.droidcommand.voice.neural.SherpaKeywordEngine
import ai.droidcommand.voice.neural.SherpaOnnxSynthesizer
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one app-side owner of voice models and the neural engines built from them. COMPILES; NEVER RUN on a
 * device (no device exists in any session so far).
 *
 * A model counts as installed only when [VoiceModelRepository] says every file matches its pinned SHA-256
 * ("every downloaded model SHA-256-checked before load"). Hashing the 114 MB voice is not free, so a
 * successful check is remembered per process, keyed by the files' sizes and modification times, and redone
 * only if they change; [refresh] must therefore run off the main thread.
 *
 * Neural features are reported through [features] only when their model is installed, so Settings offers them
 * exactly when they can work, and [ai.droidcommand.voice.VoiceSettings.normalized] switches them off otherwise.
 */
@Singleton
class VoiceRuntime @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.filesDir, "voice-models")

    val repository = VoiceModelRepository(root, HttpsFileDownloader())
        .also(VoiceModelCatalog::registerAll)

    private val lock = Any()
    private val verifiedFingerprint = HashMap<String, String>()

    @Volatile private var installed: Set<String> = emptySet()

    /** Re-checks the installed models. Blocking and CPU/disk heavy on first call after install: use Dispatchers.IO. */
    fun refresh(): VoiceFeatures = synchronized(lock) {
        installed = VoiceModelCatalog.all.map { it.id }.filter(::isInstalledLocked).toSet()
        features()
    }

    fun isInstalled(id: String): Boolean = id in installed

    /** The last result of [refresh] (everything false before the first call). */
    fun features(): VoiceFeatures =
        VoiceFeatures(neuralTts = VoiceModelCatalog.TTS_LJS_ID in installed, wakeWord = VoiceModelCatalog.KWS_ID in installed)

    private fun isInstalledLocked(id: String): Boolean {
        val dir = File(root, id)
        val model = repository.get(id) ?: return false
        val fingerprint = model.files.joinToString("|") { f -> File(dir, f.name).let { "${f.name}:${it.length()}:${it.lastModified()}" } }
        if (verifiedFingerprint[id] == fingerprint) return true
        val ok = repository.status(id) is VoiceModelStatus.Verified
        if (ok) verifiedFingerprint[id] = fingerprint else verifiedFingerprint.remove(id)
        return ok
    }

    /** Speaks with the installed neural voice; reports itself unavailable until that voice is installed and loadable. */
    fun neuralTts(): TextToSpeechEngine = InstalledNeuralTts()

    /** A wake-word detector over the microphone and the installed keyword model; unavailable until that is installed. */
    fun wakeWordDetector(): WakeWordDetector = InstalledWakeWordDetector()

    private fun modelDir(id: String) = File(root, id)

    private inner class InstalledNeuralTts : TextToSpeechEngine {
        private val delegate: ai.droidcommand.voice.NeuralTextToSpeech by lazy {
            val dir = modelDir(VoiceModelCatalog.TTS_LJS_ID)
            ai.droidcommand.voice.NeuralTextToSpeech(
                SherpaOnnxSynthesizer(
                    NeuralVoiceFiles(
                        model = File(dir, VoiceModelCatalog.LJS_MODEL),
                        tokens = File(dir, VoiceModelCatalog.LJS_TOKENS),
                        lexicon = File(dir, VoiceModelCatalog.LJS_LEXICON),
                    ),
                ),
                AudioTrackSink(),
            )
        }

        override val available: Boolean get() = VoiceModelCatalog.TTS_LJS_ID in installed && delegate.available

        override fun speak(text: String, onDone: (Boolean) -> Unit) = if (available) delegate.speak(text, onDone) else onDone(false)

        override fun stop() {
            if (VoiceModelCatalog.TTS_LJS_ID in installed) delegate.stop()
        }
    }

    private inner class InstalledWakeWordDetector : WakeWordDetector {
        private val source = AudioRecordSource()
        private val delegate: AudioWakeWordDetector by lazy {
            val dir = modelDir(VoiceModelCatalog.KWS_ID)
            val files = KeywordModelFiles(
                encoder = File(dir, VoiceModelCatalog.KWS_ENCODER),
                decoder = File(dir, VoiceModelCatalog.KWS_DECODER),
                joiner = File(dir, VoiceModelCatalog.KWS_JOINER),
                tokens = File(dir, VoiceModelCatalog.KWS_TOKENS),
                keywords = File(dir, VoiceModelCatalog.KWS_KEYWORDS),
            )
            AudioWakeWordDetector(source, SherpaKeywordEngine(files))
        }

        override val available: Boolean get() = VoiceModelCatalog.KWS_ID in installed && source.available

        override fun start(listener: WakeWordListener) = if (available) delegate.start(listener) else listener.onError("Wake word model is not installed.")

        override fun stop() {
            if (VoiceModelCatalog.KWS_ID in installed) delegate.stop()
        }
    }
}
