package ai.droidcommand.app.phonemic

import ai.droidcommand.phonemic.MicOffer
import ai.droidcommand.phonemic.MicStream
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * "Read PC screen aloud" from the phone: asks the PC (over the same pinned, authenticated session, separate opt-in
 * `:read` on the PC offer, independent of microphone sharing) for its selected text or active window, and speaks it with
 * this phone's local text-to-speech. The text is spoken and dropped: not stored, not logged. NEVER RUN on a device.
 */
object PhoneRead {
    @Volatile private var tts: TextToSpeech? = null

    fun read(ctx: Context, offerText: String, onStatus: (String) -> Unit) {
        val offer = MicOffer.parse(offerText)
        if (offer == null || offer.expired(System.currentTimeMillis() / 1000)) { onStatus("Offer missing or expired. Create a new one on the PC."); return }
        Thread({
            val msg = try {
                MicStream.connect(offer).use { s ->
                    val r = s.requestRead()
                    if (!r.ok) when (r.reason) {
                        "not-enabled" -> "Reading is off for this offer on ${offer.pcId}. Create it with :read on the PC."
                        "no-window" -> "No active window on ${offer.pcId}."
                        "nothing-readable" -> "No readable text in ${r.window.ifBlank { "the active window" }} on ${r.pc}."
                        else -> "${offer.pcId} would not read that."
                    } else { speak(ctx.applicationContext, "From ${r.pc}, window ${r.window}: ${r.text}"); "Reading from ${r.pc}, window ${r.window}." }
                }
            } catch (e: SecurityException) { "The PC refused this session."
            } catch (e: java.io.IOException) { "Could not reach ${offer.pcId}." }
            onStatus(msg)
        }, "phone-read").start()
    }

    private fun speak(ctx: Context, text: String) {
        val t = tts
        if (t != null) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pc-read"); return }
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.getDefault(); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pc-read") }
        }
    }

    fun stop() { tts?.stop() }
}
