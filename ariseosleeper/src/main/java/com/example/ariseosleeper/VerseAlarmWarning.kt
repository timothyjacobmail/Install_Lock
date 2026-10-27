package com.example.ariseosleeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

internal object VerseAlarmWarning {
    const val CHANNEL = "verse_alarm_warning"
    private const val NOTIFICATION = 4702

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "One-minute verse warning", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A notification chime and verse entry one minute before an alarm check"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
    }

    fun sync(c: Context) {
        val now = AlarmClock.now(c)
        val state = VerseAlarmStore.read(c)
        val ch = state.challenge
        val manager = c.getSystemService(NotificationManager::class.java)
        if (ch?.warning(now) != true) { manager.cancel(NOTIFICATION); return }
        val warning = VerseAlarmStore.takeWarning(c, now) ?: return
        channel(c)
        manager.notify(NOTIFICATION, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_il_alarm)
            .setContentTitle("Alarm soon · check ${warning.round + 1} of ${state.config.sessions}")
            .setContentText("Enter your verse now to complete this check before the alarm rings.")
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(VerseAlarmScheduler.content(c))
            .setFullScreenIntent(VerseAlarmScheduler.content(c), true)
            .setTimeoutAfter(warning.remaining(now)).setAutoCancel(true).build())
        // Android presents the full-screen notification or opens the screen on its tap.
    }
}
