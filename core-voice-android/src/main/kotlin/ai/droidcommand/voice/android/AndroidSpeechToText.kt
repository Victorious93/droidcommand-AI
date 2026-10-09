/*
 * Adapted in part from OpenDroid (https://github.com/Victorious93/opendroid, Apache License 2.0,
 * Copyright (c) 2026 OpenDroid Contributors): core/voice/SpeechRecognitionEngine.kt. Borrowed ideas: recognizer intent extras (locale, max results, silence lengths) and the per-code error mapping.
 * Modified: restructured behind core-voice's SpeechToText/TextToSpeechEngine seams. The ElevenLabs cloud
 * TTS path, wake-word detection and voice approvals in the original were deliberately NOT ported.
 */
package ai.droidcommand.voice.android

import ai.droidcommand.voice.SpeechToText
import ai.droidcommand.voice.SttEvent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * [SpeechToText] over the platform [SpeechRecognizer]. NEVER COMPILED OR RUN (no Android SDK in the
 * authoring environment). `SpeechRecognizer` must be used on the main thread, so every call is posted there.
 * Asks for on-device recognition (`EXTRA_PREFER_OFFLINE`); whether it is honoured, and whether the device's
 * recognition service sends audio to a server when offline models are missing, is the device vendor's
 * behaviour and is NOT verified here — do not describe this as guaranteed-offline.
 */
class AndroidSpeechToText(context: Context) : SpeechToText {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun start(listener: (SttEvent) -> Unit) {
        main.post {
            stopNow()
            val r = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let { listener(SttEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    listener(SttEvent.Final(firstResult(results).orEmpty()))
                    stopNow()
                }

                override fun onError(error: Int) {
                    listener(SttEvent.Error(describe(error)))
                    stopNow()
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Hints only; recognition services are free to ignore them.
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
            )
        }
    }

    override fun stop() {
        // stopListening() still delivers a final result; destroy only on completion/error.
        main.post { recognizer?.stopListening() }
    }

    private fun stopNow() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied. Allow it in system settings to dictate."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection on this device."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy — try again."
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error."
        else -> "Speech recognition failed (code $code)."
    }

    private companion object {
        const val SILENCE_MS = 2000L
    }
}
