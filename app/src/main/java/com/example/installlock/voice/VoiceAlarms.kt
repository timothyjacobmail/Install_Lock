package com.example.installlock.voice

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import com.example.installlock.R
import java.time.LocalTime

/** Scheduling adapted from SpeakAlert AndroidAlarmScheduler, Apache-2.0. */
object VoiceAlarms {
    const val FIRE = "voice.FIRE"
    const val DONE = "voice.DONE"
    const val SNOOZE = "voice.SNOOZE"
    const val CHANNEL = "voice_reminders"
    fun operation(c: Context, task: VoiceTask, action: String): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, VoiceReminderReceiver::class.java).setAction(action).setData(Uri.parse("installlock://voice/${task.id}/$action"))
            .putExtra("id", task.id).putExtra("scheduled", task.trigger ?: 0L), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c: Context, task: VoiceTask) {
        val at = task.trigger ?: return
        if (task.done || task.archived || task.alertedAt == at) return
        val alarms = c.getSystemService(AlarmManager::class.java)
        val op = operation(c, task, FIRE)
        val target = maxOf(at, System.currentTimeMillis() + 1500)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op) }
    }
    fun cancel(c: Context, task: VoiceTask) {
        c.getSystemService(AlarmManager::class.java).cancel(operation(c, task, FIRE))
        c.getSystemService(NotificationManager::class.java).cancel(task.id, 8100)
    }
    fun restore(c: Context) { VoiceStore.all(c).forEach { schedule(c, it) } }
    fun quiet(c: Context): Boolean {
        val p = VoiceStore.prefs(c)
        if (!p.getBoolean("quiet", false)) return false
        val hour = LocalTime.now().hour
        return hour >= 22 || hour < 7
    }
    fun deliver(c: Context, task: VoiceTask) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("voice_quiet", "Quiet task reminders", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, task.id.hashCode(), Intent(c, VoiceTasksActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val quiet = quiet(c)
        val hide = VoiceStore.prefs(c).getBoolean("private", true)
        val notification = NotificationCompat.Builder(c, if (quiet) "voice_quiet" else CHANNEL)
            .setSmallIcon(R.drawable.ic_voice_reminder).setContentTitle(task.title)
            .setContentText(if (task.recording.isNotEmpty()) "Voice memo · tap to play" else task.notes.ifBlank { task.list })
            .setContentIntent(open).setAutoCancel(false).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Done", operation(c, task, DONE)).addAction(0, "Snooze 10 min", operation(c, task, SNOOZE)).build()
        manager.notify(task.id, 8100, notification)
        if (task.speak && !quiet) VoicePlaybackService.play(c, task)
        if (VoiceStore.prefs(c).getBoolean("repeat_alert", false) && task.followUps < 3) {
            VoiceStore.save(c, task.copy(snoozeUntil = System.currentTimeMillis() + 10 * 60000, alertedAt = null, followUps = task.followUps + 1))
            // save cancels the previous alert, so restore its action notification.
            manager.notify(task.id, 8100, notification)
        }
    }
}

class VoiceReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val id = intent.getStringExtra("id")
            when (intent.action) {
                VoiceAlarms.FIRE -> id?.let {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val channel = if (VoiceAlarms.quiet(context)) "voice_quiet" else VoiceAlarms.CHANNEL
                    if (manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE)
                        VoiceStore.claim(context, it, intent.getLongExtra("scheduled", 0))?.let { task -> VoiceAlarms.deliver(context, task) }
                }
                VoiceAlarms.DONE -> id?.let { VoiceStore.complete(context, it) }
                VoiceAlarms.SNOOZE -> id?.let { VoiceStore.snooze(context, it, 10) }
                else -> VoiceAlarms.restore(context)
            }
        } catch (e: Exception) { android.util.Log.e("VoiceReminders", "Could not process reminder", e) }
    }
}
