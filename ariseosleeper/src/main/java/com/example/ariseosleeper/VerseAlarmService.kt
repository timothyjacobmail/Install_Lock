package com.example.ariseosleeper

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Audio lives outside the activity, so Back, Home and screen-off do not dismiss a challenge. */
class VerseAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var focusGranted = false
    private var vibrating = false
    private var announced = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val tick = object : Runnable {
        override fun run() {
            val ch = VerseAlarmStore.reconcile(this@VerseAlarmService).challenge
            if (ch?.ringing(AlarmClock.now(this@VerseAlarmService)) != true) {
                stopSelf()
                return
            }
            if (wake?.isHeld != true) wake?.acquire(10 * 60_000L)
            if (focusGranted && ringtone?.isPlaying == false) ringtone?.play()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        VerseAlarmScheduler.channel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VerseAlarmVolume.sync(this)
        if (VerseAlarmStore.reconcile(this).challenge?.ringing(AlarmClock.now(this)) != true) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, VerseAlarmScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for your verse")
            .setContentText("Type or recite your passage to finish this wake-up check.")
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(VerseAlarmScheduler.content(this))
            .apply { if (!announced) setFullScreenIntent(VerseAlarmScheduler.content(this@VerseAlarmService), true) }
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(VerseAlarmScheduler.NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(VerseAlarmScheduler.NOTIFICATION, notification)
        announced = true
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AriseOSleeper:verseAlarm").apply { setReferenceCounted(false) }
        if (ringtone == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = attributes
                isLooping = true
            }
        }
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { change ->
                    focusGranted = change == AudioManager.AUDIOFOCUS_GAIN
                    if (focusGranted) ringtone?.play() else ringtone?.stop()
                }.build()
            focusGranted = getSystemService(AudioManager::class.java).requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!vibrating) {
            getSystemService(Vibrator::class.java).vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 450, 700), 0), attributes)
            vibrating = true
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        getSystemService(Vibrator::class.java).cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        if (wake?.isHeld == true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(VerseAlarmScheduler.NOTIFICATION)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
