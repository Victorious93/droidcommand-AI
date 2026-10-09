package ai.droidcommand.voice.neural

import ai.droidcommand.voice.AudioSink
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock

/**
 * [AudioSink] over a streaming float-PCM [AudioTrack]. NEVER COMPILED OR RUN (no Android SDK/device here).
 *
 * [abort] may come from any thread while another is blocked in [write] or [finish]; it pauses, flushes and
 * releases the track. That a blocked `AudioTrack.write` returns promptly (with an error code) when its track
 * is released from another thread is the documented-by-behaviour expectation, NOT something verified here.
 */
class AudioTrackSink : AudioSink {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var framesWritten = 0L

    @Volatile private var aborted = false

    override fun open(sampleRate: Int) {
        synchronized(lock) {
            releaseLocked()
            aborted = false
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(min > 0) { "AudioTrack does not support $sampleRate Hz float PCM (code $min)" }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, sampleRate * Float.SIZE_BYTES)) // ~1 s of buffer
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
            this.sampleRate = sampleRate
            framesWritten = 0
            track = t
            t.play()
        }
    }

    override fun write(samples: FloatArray): Boolean {
        val t = synchronized(lock) { track } ?: return false
        var offset = 0
        while (offset < samples.size) {
            if (aborted) return false
            val n = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false
            offset += n
        }
        framesWritten += samples.size
        return !aborted
    }

    override fun finish(): Boolean {
        val t = synchronized(lock) { track } ?: return false
        val total = framesWritten
        val limit = SystemClock.elapsedRealtime() + total * 1000L / sampleRate + DRAIN_SLACK_MS
        try {
            t.stop() // in MODE_STREAM this lets already-written data play out
            while (!aborted && t.playbackHeadPosition < total && SystemClock.elapsedRealtime() < limit) {
                Thread.sleep(POLL_MS)
            }
        } catch (_: IllegalStateException) {
            return false // released by abort() underneath us
        }
        val ok = !aborted
        synchronized(lock) { releaseLocked() }
        return ok
    }

    override fun abort() {
        aborted = true
        synchronized(lock) {
            track?.let {
                try {
                    it.pause()
                    it.flush()
                } catch (_: IllegalStateException) {
                }
            }
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        track?.release()
        track = null
    }

    private companion object {
        const val POLL_MS = 20L
        const val DRAIN_SLACK_MS = 2000L
    }
}
