/*
 * Adapted in part from OpenDroid (https://github.com/Victorious93/opendroid, Apache License 2.0,
 * Copyright (c) 2026 OpenDroid Contributors): core/voice/TextToSpeechEngine.kt. Borrowed ideas: checking setLanguage() for missing data on init, and calling speak() on the main thread.
 * Modified: restructured behind core-voice's SpeechToText/TextToSpeechEngine seams. The ElevenLabs cloud
 * TTS path, wake-word detection and voice approvals in the original were deliberately NOT ported.
 */
package ai.droidcommand.voice.android

import ai.droidcommand.voice.TextToSpeechEngine
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * [TextToSpeechEngine] over the platform [TextToSpeech] using the system's installed voices (no cloning).
 * NEVER COMPILED OR RUN (no Android SDK in the authoring environment). Initialization is asynchronous:
 * [available] is false until the engine reports success, and a [speak] before then reports `onDone(false)`.
 */
class AndroidTextToSpeech(context: Context) : TextToSpeechEngine {
    @Volatile private var ready = false
    private val callbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // Ready only if the engine started AND has data for the device language; otherwise it would
            // report available and then stay silent.
            val language = if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.getDefault()) else TextToSpeech.LANG_NOT_SUPPORTED
            ready = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                callbacks.remove(utteranceId)?.invoke(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                callbacks.remove(utteranceId)?.invoke(false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                callbacks.remove(utteranceId)?.invoke(false)
            }
        })
    }

    override val available: Boolean get() = ready

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        if (!ready) return onDone(false)
        val id = java.util.UUID.randomUUID().toString()
        callbacks[id] = onDone
        main.post {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                callbacks.remove(id)?.invoke(false)
            }
        }
    }

    override fun stop() {
        main.post { tts.stop() }
    }

    /** Call when the owning scope ends; the engine holds a service connection. */
    fun shutdown() {
        tts.shutdown()
    }
}
