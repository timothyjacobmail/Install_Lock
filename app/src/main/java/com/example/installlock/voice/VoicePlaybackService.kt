package com.example.installlock.voice

import android.app.*
import android.content.*
import android.media.MediaPlayer
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.example.installlock.R
import java.io.File
import java.util.Locale

class VoicePlaybackService : Service() {
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("voice_playback", "Spoken reminders", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(8102, NotificationCompat.Builder(this, "voice_playback").setSmallIcon(R.drawable.ic_voice_reminder)
            .setContentTitle("Playing reminder").addAction(0, "Stop", stop).build())
        val task = VoiceStore.all(this).find { it.id == intent?.getStringExtra("id") }
        if (task == null || VoiceAlarms.quiet(this)) { stopSelf(); return START_NOT_STICKY }
        player?.release(); tts?.shutdown()
        val file = File(filesDir, "voice-recordings/${task.recording}")
        if (task.recording.isNotEmpty() && file.isFile) {
            try {
                player = MediaPlayer().apply { setDataSource(file.path); setOnCompletionListener { stopSelf() }; prepare(); start() }
            } catch (_: Exception) { stopSelf() }
        } else {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val offline = tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
                    if (offline == null) { stopSelf(); return@TextToSpeech }
                    tts?.voice = offline
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { handler.post { stopSelf() } }
                        @Deprecated("platform callback") override fun onError(id: String?) { handler.post { stopSelf() } }
                    })
                    tts?.speak(task.title, TextToSpeech.QUEUE_FLUSH, null, task.id)
                } else stopSelf()
            }
        }
        handler.removeCallbacksAndMessages(null); handler.postDelayed({ stopSelf() }, 60000)
        return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); player?.release(); tts?.shutdown(); super.onDestroy() }
    companion object {
        fun play(c: Context, task: VoiceTask) {
            try { c.startForegroundService(Intent(c, VoicePlaybackService::class.java).putExtra("id", task.id)) }
            catch (_: RuntimeException) { /* The visible notification remains available if Android prevents playback. */ }
        }
    }
}
