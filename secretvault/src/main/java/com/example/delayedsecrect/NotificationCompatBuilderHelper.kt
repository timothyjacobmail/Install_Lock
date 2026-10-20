package com.example.delayedsecrect

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationCompatBuilderHelper {
    fun build(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Secret visible")
            .setContentText("Tap the floating box to view it")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}