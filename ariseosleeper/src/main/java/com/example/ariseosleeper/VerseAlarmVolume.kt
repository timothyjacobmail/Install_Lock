package com.example.ariseosleeper

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/** Do not use DISALLOW_ADJUST_VOLUME: that restriction also mutes the entire device.
 * Restore only STREAM_ALARM, without interfering with call or media volume. */
internal object VerseAlarmVolume {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var observing = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { context?.let(::sync) }
    }
    private val check = Runnable { context?.let(::enforce) }

    fun sync(c: Context) {
        val app = c.applicationContext
        // All observer and polling ownership stays on the main thread.
        handler.post {
            context = app
            handler.removeCallbacks(check)
            enforce(app)
        }
    }

    private fun enforce(c: Context) {
        handler.removeCallbacks(check)
        val active = VerseAlarmStore.read(c).challenge != null
        try {
            val prefs = c.createDeviceProtectedStorageContext()
                .getSharedPreferences("verse_alarm_volume", Context.MODE_PRIVATE)
            val audio = c.getSystemService(AudioManager::class.java)
            AlarmVolumeHold(object : AlarmVolumeAccess {
                override fun savedLevel(): Int? = if (prefs.contains("level")) prefs.getInt("level", 0) else null
                override fun saveLevel(level: Int?) {
                    val edit = prefs.edit()
                    if (level == null) edit.remove("level") else edit.putInt("level", level)
                    check(edit.commit()) { "Could not save the held alarm volume." }
                }
                override fun currentLevel() = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                override fun minimumLevel() = audio.getStreamMinVolume(AudioManager.STREAM_ALARM)
                override fun maximumLevel() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                override fun setLevel(level: Int) = audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            }).reconcile(active)
            if (active && !observing) {
                c.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
                observing = true
            }
        } catch (e: RuntimeException) {
            Log.e("VerseAlarm", "Could not maintain the alarm volume", e)
        } finally {
            if (active) handler.postDelayed(check, 250)
            else if (observing) {
                c.contentResolver.unregisterContentObserver(observer)
                observing = false
            }
        }
    }
}
