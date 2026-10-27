package com.example.ariseosleeper

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log

internal object VerseAlarmScheduler {
    const val FIRE = "com.example.ariseosleeper.VERSE_ALARM"
    const val CHANGE = "com.example.ariseosleeper.VERSE_ALARM_CHANGE"
    const val WARN = "com.example.ariseosleeper.VERSE_ALARM_WARN"
    const val CHANNEL = "verse_alarm"
    const val NOTIFICATION = 4701

    fun open(c: Context): Intent = Intent(c, VerseAlarmActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun content(c: Context): PendingIntent = PendingIntent.getActivity(c, 4701, open(c),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun operation(c: Context, action: String) = PendingIntent.getBroadcast(c, 4701,
        Intent(c, VerseAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Verse wake-up alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily alarm and verse follow-up challenges"
                setSound(null, null) // The foreground service owns looping alarm audio.
            })
        VerseAlarmWarning.channel(c)
    }

    fun readiness(c: Context): List<String> = buildList {
        channel(c)
        if (Build.VERSION.SDK_INT >= 31 && !c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            add("Allow Alarms & reminders")
        val manager = c.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH)
            add("Allow high-priority alarm notifications")
        if (manager.getNotificationChannel(VerseAlarmWarning.CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH)
            add("Allow one-minute verse warning notifications")
        if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) add("Allow full-screen alarms")
        if (c.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0)
            add("Raise the alarm volume above zero")
    }

    fun reschedule(c: Context, timeChanged: Boolean = false, boot: Boolean = false) {
        val now = AlarmClock.now(c)
        val s = VerseAlarmStore.reconcile(c, now, timeChanged)
        VerseAlarmVolume.sync(c)
        val manager = c.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        val ch = s.challenge
        val next = when {
            ch != null -> now.wall + if (ch.ringing(now)) (if (boot) 1000L else 60_000L) else ch.remaining(now)
            s.config.enabled -> s.nextAlarm
            else -> 0L
        }
        val alarm = operation(c, FIRE)
        if (next == 0L) manager.cancel(alarm)
        else try {
            if (canExact) manager.setAlarmClock(AlarmManager.AlarmClockInfo(next, content(c)), alarm)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
        } catch (e: SecurityException) {
            Log.w("VerseAlarm", "Exact alarm permission unavailable", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
        }
        val warning = operation(c, WARN)
        val warningAt = s.nextWarningAt(now)
        if (warningAt == null) manager.cancel(warning)
        else try {
            if (canExact) manager.setAlarmClock(AlarmManager.AlarmClockInfo(warningAt, content(c)), warning)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warningAt, warning)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warningAt, warning) }
        val change = operation(c, CHANGE)
        if (s.countdown == null || ch != null) manager.cancel(change)
        else {
            val at = now.elapsed + s.countdown.remaining(now).coerceAtLeast(1000)
            try {
                if (canExact) manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, change)
                else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, change)
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, change) }
        }
    }

    /** Called after a user action or delivery of an exact alarm. */
    fun sync(c: Context) {
        VerseAlarmStore.reconcile(c)
        VerseAlarmWarning.sync(c)
        reschedule(c)
        val ch = VerseAlarmStore.read(c).challenge
        if (ch?.ringing(AlarmClock.now(c)) == true) {
            try { c.startForegroundService(Intent(c, VerseAlarmService::class.java)) }
            catch (e: RuntimeException) { Log.e("VerseAlarm", "Could not start alarm audio", e) }
        } else c.stopService(Intent(c, VerseAlarmService::class.java))
    }
}

class VerseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            VerseAlarmScheduler.FIRE, VerseAlarmScheduler.CHANGE, VerseAlarmScheduler.WARN -> VerseAlarmScheduler.sync(context)
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                VerseAlarmScheduler.reschedule(context, timeChanged = true, boot = true)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                VerseAlarmScheduler.reschedule(context, boot = true)
        }
    }
}
