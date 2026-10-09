package ai.droidcommand.voice

/**
 * The voice/wake-word models this build knows how to download. Every entry was fetched from the sherpa-onnx
 * project's official GitHub release (https://github.com/k2-fsa/sherpa-onnx/releases) on 2026-10-09, its archive
 * and each extracted file hashed, and run with the matching sherpa-onnx 1.13.8 build (see the 2026-10-09
 * audit addendum for what that run did and did not show).
 *
 * Licensing rule (owner decision, 2026-10-09): **nothing that needs espeak-ng data (GPL-3.0)**. That excludes
 * Piper and Kokoro voices; a test enforces that no file here is espeak-ng data. The license strings record what
 * each model's own README declares; they are not legal advice and the upstream training-data terms
 * (GigaSpeech, LJ Speech) were not separately audited.
 */
object VoiceModelCatalog {
    const val KWS_ID = "kws-zipformer-gigaspeech-3.3m"
    const val TTS_LJS_ID = "tts-vits-ljs"

    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val KWS_URL = "$RELEASES/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    private const val KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    private const val LJS_URL = "$RELEASES/tts-models/vits-ljs.tar.bz2"

    // File names inside an installed model's directory. The app maps these to the engine's file classes.
    const val KWS_ENCODER = "encoder.int8.onnx"
    const val KWS_DECODER = "decoder.int8.onnx"
    const val KWS_JOINER = "joiner.int8.onnx"
    const val KWS_TOKENS = "tokens.txt"
    const val KWS_KEYWORDS = "keywords.txt"
    const val LJS_MODEL = "vits-ljs.onnx"
    const val LJS_LEXICON = "lexicon.txt"
    const val LJS_TOKENS = "tokens.txt"

    /**
     * Wake word. About 17.6 MB download (5.2 MB installed, int8). The wake phrases are the ones the model's own
     * keywords.txt ships pre-tokenized ("HELLO WORLD", "HI GOOGLE", "HEY SIRI", "ALEXA", "PLAY MUSIC", ...); a
     * custom phrase would need its own tokenization and is not offered.
     */
    val kws = VoiceModel(
        id = KWS_ID,
        displayName = "Wake word (small, English)",
        files = listOf(
            VoiceFile(KWS_ENCODER, KWS_URL, "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678", "$KWS_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            VoiceFile(KWS_DECODER, KWS_URL, "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a", "$KWS_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            VoiceFile(KWS_JOINER, KWS_URL, "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c", "$KWS_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            VoiceFile(KWS_TOKENS, KWS_URL, "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53", "$KWS_DIR/tokens.txt"),
            VoiceFile(KWS_KEYWORDS, KWS_URL, "b1740c4925d7c83337de2cffa04e8d35450fab2af8f583e65c54aad6257c4bd6", "$KWS_DIR/keywords.txt"),
        ),
        license = "Apache-2.0 (as declared in the model's README; trained on GigaSpeech)",
        archiveSha256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
    )

    /**
     * English voice (single LJ Speech speaker, lexicon-based, no espeak-ng). About 109 MB download, 114 MB
     * installed. Known quality caveat: sherpa-onnx logs "Unknown token" for some characters in its lexicon, so
     * a few sounds can be dropped; judge the voice on a device before promoting it.
     */
    val ttsLjs = VoiceModel(
        id = TTS_LJS_ID,
        displayName = "English voice (LJ Speech, ~110 MB)",
        files = listOf(
            VoiceFile(LJS_MODEL, LJS_URL, "5bbd273797a9ecf8d94bd6ec02ad16cb41cbb85f055ad98d528ced3e44c9b31a", "vits-ljs/vits-ljs.onnx"),
            VoiceFile(LJS_LEXICON, LJS_URL, "bdccfc6da71c45c48e2e0056fcf0aab760577c5f959f6c1b5eb3e3e916fd5a0e", "vits-ljs/lexicon.txt"),
            VoiceFile(LJS_TOKENS, LJS_URL, "5fee2c6b238d712287f2ecb08f34a8a8b413bcb7390862ef6fb6fd6f0f8d3a17", "vits-ljs/tokens.txt"),
        ),
        license = "Apache-2.0 (as declared in the model's README; LJ Speech dataset, CMU-IPA lexicon)",
        archiveSha256 = "78f7df445fcd42d1dd6df2c78c66c2c2fee8b7abecc6ae255a5950097a6558bc",
    )

    val all: List<VoiceModel> = listOf(ttsLjs, kws)

    /** Registers every catalog entry that is not already registered. */
    fun registerAll(repository: VoiceModelRepository) {
        all.filter { repository.get(it.id) == null }.forEach(repository::register)
    }
}
