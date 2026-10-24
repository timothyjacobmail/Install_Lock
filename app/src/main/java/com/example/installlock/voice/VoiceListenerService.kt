package com.example.installlock.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.example.installlock.R
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** All recognition happens in Vosk. No Android/cloud speech fallback exists. */
class VoiceListenerService : Service(), SensorEventListener {
    private val alive = AtomicBoolean(false)
    @Volatile private var commandRequested = false
    @Volatile private var durationCapture = false
    @Volatile private var practiceCapture = false
    @Volatile private var captureId = ""
    @Volatile private var capturing = false
    private var audio: AudioRecord? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastShake = 0L
    private var peakAt = 0L
    private var peaks = 0
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { VoiceStore.prefs(this).edit().putBoolean("running", false).apply(); stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { event("error", "Allow microphone access first."); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == CAPTURE || intent?.action == CAPTURE_TIME || intent?.action == CAPTURE_PRACTICE) {
            practiceCapture = intent.action == CAPTURE_PRACTICE
            captureId = intent.getStringExtra("capture_id").orEmpty()
            durationCapture = intent.action == CAPTURE_TIME || practiceCapture
            commandRequested = true
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice_listener", "Offline voice listener", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 8101, Intent(this, VoiceTasksActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 8101, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(8101, NotificationCompat.Builder(this, "voice_listener").setSmallIcon(R.drawable.ic_voice_reminder)
                .setContentTitle("InstallLock · offline voice").setContentText("Offline activation enabled · tap to manage or pause")
                .setContentIntent(open).setOngoing(true).addAction(0, "Pause listening", pause).build())
        } catch (e: RuntimeException) { event("error", "Android prevented microphone access. Open Voice Tasks and try again."); stopSelf(); return START_NOT_STICKY }
        if (alive.compareAndSet(false, true)) {
            workerRunning = true
            active = true
            val sensors = getSystemService(SensorManager::class.java)
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            Thread({ listen() }, "InstallLockOfflineSpeech").start()
        }
        return START_NOT_STICKY
    }
    private fun modelPath(): String {
        event("status", "Preparing offline speech…")
        return VoiceModel.prepare(this)
    }
    @Suppress("MissingPermission")
    private fun listen() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(modelPath())
            if (!alive.get()) return
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            audio = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened." }
            val buffer = ShortArray(1600)
            var commandMode = false
            var deadline = 0L
            var lastPartial = ""
            fun reset(command: Boolean) {
                recognizer?.close()
                recognizer = when {
                    !command -> Recognizer(model, 16000f, "[\"hey reminder\", \"[unk]\"]")
                    durationCapture && !practiceCapture && VoiceTimePractice.samples(this).isNotEmpty() -> Recognizer(model, 16000f, VoiceTimePractice.grammar(this))
                    else -> Recognizer(model, 16000f)
                }
                commandMode = command; capturing = command
                deadline = SystemClock.elapsedRealtime() + 15000
                lastPartial = ""
                if (command) { event("status", if (practiceCapture) "Listening… say the displayed phrase" else if (durationCapture) "Listening… say five minutes or five hours" else "Listening… speak your command"); VoiceFeedback.ready(this) }
            }
            reset(commandRequested); commandRequested = false
            ready = true
            if (!commandMode) event("status", if (VoiceStore.prefs(this).getBoolean("wake", false)) "Wake phrase ready" else "Shake detection ready · shake three times")
            while (alive.get()) {
                if (commandRequested) { commandRequested = false; reset(true) }
                val p = VoiceStore.prefs(this)
                if (!commandMode && !p.getBoolean("wake", false)) {
                    if (!p.getBoolean("running", false)) break
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    Thread.sleep(150); continue
                }
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) recorder.startRecording()
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) { if (!alive.get()) break; throw IllegalStateException("Microphone became unavailable.") }
                val final = recognizer!!.acceptWaveForm(buffer, n)
                val json = JSONObject(if (final) recognizer!!.result else recognizer!!.partialResult)
                val text = json.optString(if (final) "text" else "partial").trim()
                if (!commandMode) {
                    if (text == "hey reminder") { reset(true); launchCapture(null) }
                } else {
                    if (text.isNotBlank() && text != lastPartial) { lastPartial = text; event("partial", text) }
                    if ((final && text.isNotBlank()) || SystemClock.elapsedRealtime() > deadline) {
                        val result = if (text.isNotBlank()) text else lastPartial
                        if (result.isNotBlank()) {
                            if (practiceCapture) event("practice", result)
                            else if (durationCapture) event("duration", result)
                            else { event("final", result); launchCapture(result) }
                        }
                        else event("error", if (durationCapture) "No duration heard." else "No speech heard. Tap Dictate to try again.")
                        reset(false)
                        if (!p.getBoolean("running", false)) break
                    }
                }
            }
        } catch (e: Exception) {
            if (alive.get()) { event("error", "Offline speech unavailable: ${e.message}"); VoiceStore.prefs(this).edit().putString("speech_error", e.message ?: "Speech error").apply() }
        } finally {
            capturing = false
            try { audio?.stop() } catch (_: Exception) {}
            audio?.release(); audio = null; recognizer?.close(); model?.close()
            main.post { workerRunning = false; stopSelf() }
        }
    }
    private fun launchCapture(text: String?) {
        main.post {
            try {
                startActivity(Intent(this, VoiceTasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("transcript", text).putExtra("listening", text == null))
            } catch (_: RuntimeException) { /* Notification remains the explicit entry point. */ }
        }
    }
    private fun event(kind: String, text: String) {
        sendBroadcast(Intent(EVENT).setPackage(packageName).putExtra("kind", kind).putExtra("text", text).putExtra("capture_id", captureId))
    }
    override fun onSensorChanged(event: SensorEvent) {
        val p = VoiceStore.prefs(this)
        if (!p.getBoolean("shake", false) || !p.getBoolean("running", false) || capturing) return
        val now = SystemClock.elapsedRealtime()
        val v = event.values
        val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / SensorManager.GRAVITY_EARTH
        val threshold = p.getFloat("shake_threshold", 2.7f)
        if (g < threshold || now - lastShake < 10000 || now - peakAt < 180) return
        peaks = if (now - peakAt < 1200) peaks + 1 else 1; peakAt = now
        if (peaks >= 3) {
            peaks = 0; lastShake = now
            if (p.getBoolean("shake_memo", true)) {
                capturing = true
                try { startActivity(VoiceShakeAction.intent(this)) }
                catch (_: RuntimeException) { capturing = false; event("error", "Open Voice Tasks to record a memo.") }
                // Allow another attempt if Android prevented the activity from opening.
                main.postDelayed({ if (alive.get()) capturing = false }, 10000)
            } else { commandRequested = true; launchCapture(null) }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() {
        alive.set(false); active = false; ready = false
        VoiceStore.prefs(this).edit().putBoolean("running", false).apply()
        try { audio?.stop() } catch (_: Exception) {}
        getSystemService(SensorManager::class.java).unregisterListener(this)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    companion object {
        const val EVENT = "com.example.installlock.voice.EVENT"
        const val CAPTURE = "voice.CAPTURE"
        const val CAPTURE_TIME = "voice.CAPTURE_TIME"
        const val CAPTURE_PRACTICE = "voice.CAPTURE_PRACTICE"
        const val STOP = "voice.STOP"
        @Volatile var active = false
        @Volatile var ready = false
        @Volatile var workerRunning = false
        fun start(c: Context, capture: Boolean = false, duration: Boolean = false) {
            c.startForegroundService(Intent(c, VoiceListenerService::class.java).setAction(if (duration) CAPTURE_TIME else if (capture) CAPTURE else "voice.START"))
        }
        fun pause(c: Context) { VoiceStore.prefs(c).edit().putBoolean("running", false).apply(); c.stopService(Intent(c, VoiceListenerService::class.java)) }
    }
}
