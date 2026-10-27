package com.example.ariseosleeper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

internal object AlarmClock {
    @Volatile private var processBoot: Int? = null

    private fun boot(c: Context): Int = processBoot ?: synchronized(this) {
        processBoot ?: Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, 0)
            .also { processBoot = it }
    }

    fun now(c: Context) = AlarmClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(c))
}
