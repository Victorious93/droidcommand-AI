package ai.droidcommand.app.phonemic

import ai.droidcommand.phonemic.FRAME_BYTES
import ai.droidcommand.phonemic.LiveQueue
import ai.droidcommand.phonemic.MicOffer
import ai.droidcommand.phonemic.MicStream
import ai.droidcommand.phonemic.SAMPLE_RATE
import ai.droidcommand.phonemic.ShareState
import ai.droidcommand.phonemic.ShareStateMachine
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI shows. The offer's secret is never part of this. */
data class PhoneMicStatus(
    val state: ShareState = ShareState.IDLE,
    val pcName: String = "",
    val note: String = "",
)

object PhoneMicBus {
    private val _status = MutableStateFlow(PhoneMicStatus())
    val status = _status.asStateFlow()
    internal fun set(s: PhoneMicStatus) {
        _status.value = s
    }
}

/**
 * Streams this phone's microphone to the one PC named in an offer the owner pasted, until Stop. COMPILES only
 * after a build; NEVER RUN on a device.
 *
 * Rules honoured: started from a visible screen with RECORD_AUDIO already granted; foreground service type
 * microphone with an ongoing notification that names the PC and has Stop and Mute; silenced capture (call,
 * another app, privacy switch) is detected and reported to the PC as "silenced"; audio goes only into a
 * bounded queue and the TLS socket, never to disk or logs; the offer lives in memory only.
 */
class PhoneMicService : Service() {
    private val sm = ShareStateMachine()
    private val queue = LiveQueue()

    @Volatile private var offer: MicOffer? = null

    @Volatile private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var callback: AudioManager.AudioRecordingCallback? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing("Stopped")
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                toggleMute()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (worker != null) return START_NOT_STICKY
                val o = MicOffer.parse(intent.getStringExtra(EXTRA_OFFER).orEmpty())
                if (o == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    PhoneMicBus.set(PhoneMicStatus(ShareState.STOPPED, note = if (o == null) "That is not a valid offer." else "Microphone permission is off."))
                    stopSelf()
                    return START_NOT_STICKY
                }
                offer = o
                sm.start()
                publish("Connecting…")
                startForegroundCompat()
                worker = Thread({ run(o) }, "phone-mic").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        return START_NOT_STICKY // never restarted by the system: consent does not survive a kill
    }

    private fun run(o: MicOffer) {
        var stream: MicStream? = null
        var rec: AudioRecord? = null
        try {
            stream = MicStream.connect(o)
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "This phone cannot record 16 kHz audio" }
            @Suppress("MissingPermission")
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 8),
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy or blocked" }
            record = rec
            watchSilencing(rec, stream)
            rec.startRecording()
            sm.connected()
            publish("Sharing")
            val buf = ByteArray(FRAME_BYTES)
            var sent = ShareState.SHARING
            while (sm.state != ShareState.STOPPED) {
                var n = 0
                while (n < FRAME_BYTES) {
                    val r = rec.read(buf, n, FRAME_BYTES - n)
                    if (r < 0) throw java.io.IOException("capture error $r")
                    n += r
                }
                if (sm.state != sent) {
                    stream.control(sm.wire)
                    sent = sm.state
                }
                if (!sm.capturing) {
                    queue.clear()
                    continue
                } // muted or silenced: nothing leaves the phone
                queue.push(buf.copyOf())
                while (true) stream.sendPcm(queue.pop() ?: break)
            }
        } catch (e: Exception) {
            if (sm.state != ShareState.STOPPED) {
                val msg = when (e) {
                    is SecurityException -> "The PC refused this session. Create a new offer on the PC."
                    is java.io.IOException -> "The PC disconnected. Tap Use this phone as microphone to reconnect."
                    else -> e.message ?: "Sharing stopped."
                }
                stopSharing(msg)
            }
        } finally {
            queue.clear()
            try {
                rec?.stop()
            } catch (_: IllegalStateException) {
            }
            rec?.release()
            record = null
            stream?.close()
        }
    }

    private fun watchSilencing(rec: AudioRecord, stream: MicStream) {
        if (Build.VERSION.SDK_INT < 29) return
        val am = getSystemService(AudioManager::class.java)
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
                sm.silenced(mine.isClientSilenced)
                publish(if (sm.state == ShareState.SILENCED) "Silenced by Android (a call, another app or the microphone privacy switch)" else "")
            }
        }
        callback = cb
        am.registerAudioRecordingCallback(cb, null)
    }

    private fun toggleMute() {
        if (sm.state == ShareState.MUTED) sm.unmute() else sm.mute()
        publish("")
    }

    private fun stopSharing(note: String) {
        sm.stop()
        callback?.let { getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it) }
        callback = null
        PhoneMicBus.set(PhoneMicStatus(ShareState.STOPPED, offer?.pcId.orEmpty(), note))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (sm.state != ShareState.STOPPED) sm.stop()
        super.onDestroy()
    }

    private fun publish(note: String) {
        PhoneMicBus.set(PhoneMicStatus(sm.state, offer?.pcId.orEmpty(), note))
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun startForegroundCompat() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone microphone", NotificationManager.IMPORTANCE_LOW))
        fun act(a: String, label: String) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(this, a.hashCode(), Intent(this, PhoneMicService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val pc = offer?.pcId ?: "your PC"
        val text = when (sm.state) {
            ShareState.CONNECTING -> "Connecting to $pc…"
            ShareState.MUTED -> "Muted. $pc hears nothing."
            ShareState.SILENCED -> "Android is silencing the microphone. $pc hears nothing."
            else -> "Your microphone is being sent to $pc."
        }
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Phone microphone")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(act(ACTION_STOP, "Stop"))
            .addAction(act(ACTION_MUTE, if (sm.state == ShareState.MUTED) "Unmute" else "Mute"))
            .build()
    }

    companion object {
        const val ACTION_START = "ai.droidcommand.app.phonemic.START"
        const val ACTION_STOP = "ai.droidcommand.app.phonemic.STOP"
        const val ACTION_MUTE = "ai.droidcommand.app.phonemic.MUTE"
        const val EXTRA_OFFER = "offer"
        private const val CHANNEL = "phone_mic"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context, offer: String) =
            ctx.startForegroundService(Intent(ctx, PhoneMicService::class.java).setAction(ACTION_START).putExtra(EXTRA_OFFER, offer))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, PhoneMicService::class.java).setAction(ACTION_STOP))
        fun mute(ctx: Context) = ctx.startService(Intent(ctx, PhoneMicService::class.java).setAction(ACTION_MUTE))
    }
}
