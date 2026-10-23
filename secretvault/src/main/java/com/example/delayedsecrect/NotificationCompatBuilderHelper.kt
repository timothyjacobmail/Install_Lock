package com.example.delayedsecrect

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationCompatBuilderHelper {
    fun build(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Floating password")
            .setContentText("Tap a password field, then use Enter password in the floating window")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
