package ai.droidcommand.voice.neural

import ai.droidcommand.voice.AudioFrameSource
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * [AudioFrameSource] over [AudioRecord] (16 kHz mono float PCM, ~100 ms frames). NEVER COMPILED OR RUN.
 * The caller must hold RECORD_AUDIO; without it [start] throws and the detector reports the error.
 * Audio lives only in the one reused frame buffer handed to the callback; nothing is stored or sent.
 */
class AudioRecordSource(override val sampleRate: Int = 16_000) : AudioFrameSource {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    override val available: Boolean
        get() = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT) > 0

    @SuppressLint("MissingPermission") // checked by the caller; a SecurityException surfaces as a start error
    override fun start(onFrame: (FloatArray) -> Unit) {
        synchronized(lock) {
            if (running) return
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(min > 0) { "AudioRecord does not support $sampleRate Hz float PCM (code $min)" }
            val frame = sampleRate / 10
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(min, frame * Float.SIZE_BYTES * 4),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                error("Microphone could not be opened")
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({
                val buf = FloatArray(frame)
                while (running) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) break
                    if (n > 0) onFrame(if (n == buf.size) buf else buf.copyOf(n))
                }
            }, "wake-word-audio").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            record?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            record = null
            t = thread
            thread = null
        }
        if (t != null && t !== Thread.currentThread()) t.join(500)
    }
}
