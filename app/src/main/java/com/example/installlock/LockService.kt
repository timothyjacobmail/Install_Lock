package com.example.installlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class LockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val accessibilityObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { Policy.sweep(this@LockService) }
    }
    private var observingAccessibility = false
    private var scheduledBoundary = 0L
    private val boundaryAlarm = AlarmManager.OnAlarmListener {
        scheduledBoundary = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            scheduledBoundary = 0L
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun scheduleBoundary(): Long {
        val now = System.currentTimeMillis()
        val cooldown = WatchdogStore.remaining(this)
        val next = minOf(CurfewRules.nextBoundary(now, CurfewStore.zone(), CurfewStore.appealedNight(this)),
            if (cooldown > 0) now + cooldown else Long.MAX_VALUE)
        if (scheduledBoundary != next) {
            val alarms = getSystemService(AlarmManager::class.java)
            alarms.cancel(boundaryAlarm)
            // Listener alarms need no exact-alarm permission. Screen-on reconciles immediately
            // after Doze, and service/boot restarts recreate this process-owned alarm.
            alarms.setExact(AlarmManager.RTC_WAKEUP, next, "Install Lock curfew", boundaryAlarm, handler)
            scheduledBoundary = next
        }
        return (next - now).coerceAtLeast(100L)
    }

    private val tick = object : Runnable {
        override fun run() {
            Store.applyDueKeywordRemovals(this@LockService)
            Policy.sweep(this@LockService)
            val untilBoundary = scheduleBoundary()
            handler.postDelayed(this, minOf(5000L, untilBoundary,
                Store.timingConfig(this@LockService).milliseconds(TimingOption.POLICY_INTERVAL)))
        }
    }

    // Fires instantly when an app is installed so it's locked before you can open it
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.data?.schemeSpecificPart == Policy.CHROME) Policy.syncChrome(c, force = true)
            Policy.sweep(c)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("lock", "Install lock", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "lock")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Install Lock is active")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        registerReceiver(
            pkgReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        )
        registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver)
            observingAccessibility = true
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, accessibilityObserver)
        } catch (_: SecurityException) {
            // Service disconnect callbacks and the repeating sweep still enforce the guard.
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Includes opening Install Lock, boot, and sticky restarts after process death.
        Policy.syncChrome(this, force = true)
        // Re-schedule immediately when settings change; keep exactly one repeating tick.
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(pkgReceiver)
        unregisterReceiver(wakeReceiver)
        getSystemService(AlarmManager::class.java).cancel(boundaryAlarm)
        if (observingAccessibility) contentResolver.unregisterContentObserver(accessibilityObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(c: Context) {
            c.startForegroundService(Intent(c, LockService::class.java))
        }
    }
}
