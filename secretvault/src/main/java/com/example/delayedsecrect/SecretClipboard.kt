package com.example.delayedsecrect

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.UUID
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** One clipboard lease per process. Only random ownership tokens and deadlines go to disk. */
object SecretClipboard {
    private const val PREFS = "secret_clipboard_cleanup"
    private const val CHANNEL = "password_clipboard_cleanup"
    private const val NOTIFICATION = 7194
    private val handler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var lease: ClipboardLease? = null
    private var pasteValue: String? = null
    private var detectPaste = false
    private var monitoringChanged: (() -> Unit)? = null
    private var requestFocus: (() -> Unit)? = null
    private var foregroundActivity = WeakReference<Activity>(null)
    private val focusListeners = WeakHashMap<Activity, ViewTreeObserver.OnWindowFocusChangeListener>()
    private var lastFocusRequest = Long.MIN_VALUE
    private val alarm = AlarmManager.OnAlarmListener { reconcile() }
    private val due = Runnable { reconcile() }
    val watchingPaste: Boolean get() = detectPaste && pasteValue != null && lease != null

    fun initialize(context: Context) {
        if (application != null) return
        val app = context.applicationContext as Application
        application = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { token ->
            lease = ClipboardLease(token, prefs.getLong("copiedAt", 0), prefs.getLong("deadline", 0), prefs.getInt("boot", -1))
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                watchActivity(activity)
                activity.window.decorView.post { clearFromFocusedActivity(activity) }
            }
            override fun onActivityDestroyed(activity: Activity) {
                focusListeners.remove(activity)?.let {
                    val observer = activity.window.decorView.viewTreeObserver
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(it)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity.get() == activity) foregroundActivity.clear()
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        schedule()
    }

    private fun watchActivity(activity: Activity) {
        foregroundActivity = WeakReference(activity)
        if (!focusListeners.containsKey(activity)) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused) clearFromFocusedActivity(activity)
            }
            focusListeners[activity] = listener
            activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
    }

    /** Called by Install Lock's existing accessibility service; no extra service is enabled. */
    fun attachAccessibility(context: Context, changed: () -> Unit, focus: () -> Unit) {
        initialize(context)
        monitoringChanged = changed
        requestFocus = focus
        changed()
        reconcile()
    }

    fun detachAccessibility() {
        requestFocus = null
        monitoringChanged = null
    }

    fun copyWithOptions(context: Context, value: String, result: (String) -> Unit = {}) {
        initialize(context)
        if (context is Activity) watchActivity(context)
        val token = UUID.randomUUID().toString()
        val now = SystemClock.elapsedRealtime()
        copySecretToClipboard(context, value, token)
        lease = ClipboardLease(token, now, now + 60_000L, boot())
        pasteValue = value
        detectPaste = false
        lastFocusRequest = Long.MIN_VALUE
        application?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        persist()
        monitoringChanged?.invoke()
        schedule()
        var chosen = false
        val dialog = AlertDialog.Builder(context)
            .setTitle("Password copied")
            .setMessage("How long do you want the password in your clipboard?\n\n" +
                "Until pasted needs Install Lock accessibility. If a paste cannot be detected, " +
                "it clears after 10 minutes. Android may clear earlier. Closing this prompt without a choice clears the copy.\n\n" +
                "Passwords are marked sensitive. Gboard's saved or pinned clipboard history cannot be deleted by this app. " +
                "Android may briefly show a cleanup screen. If Android prevents automatic cleanup, tap the cleanup notification.")
            .setPositiveButton("Until pasted") { _, _ ->
                chosen = select(token, ClipboardLifetime.UNTIL_PASTED)
                result(if (!chosen) "Copy expired. Copy the password again."
                    else if (requestFocus == null) "Copied for up to 10 minutes. Paste detection needs Install Lock accessibility enabled."
                    else "Copied until a paste is detected (10-minute maximum).")
            }
            .setNeutralButton("5 min") { _, _ ->
                chosen = select(token, ClipboardLifetime.FIVE_MINUTES)
                result(if (chosen) "Password copied for up to 5 minutes." else "Copy expired. Copy the password again.")
            }
            .setNegativeButton("10 min") { _, _ ->
                chosen = select(token, ClipboardLifetime.TEN_MINUTES)
                result(if (chosen) "Password copied for up to 10 minutes." else "Copy expired. Copy the password again.")
            }
            .create()
        dialog.setOnDismissListener {
            if (!chosen && lease?.token == token) expire()
        }
        try {
            if (context !is Activity) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (_: RuntimeException) {
            if (lease?.token == token) expire()
            result("Could not open the clipboard duration chooser. The copied password will be cleared.")
        }
    }

    private fun select(token: String, lifetime: ClipboardLifetime): Boolean {
        val current = lease?.takeIf { it.token == token && !it.expired(SystemClock.elapsedRealtime(), boot()) } ?: return false
        lease = current.selected(lifetime)
        detectPaste = lifetime == ClipboardLifetime.UNTIL_PASTED
        if (!detectPaste) pasteValue = null
        persist()
        monitoringChanged?.invoke()
        schedule()
        return true
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val value = pasteValue ?: return
        if (!watchingPaste || event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        // Only inspect edit events during this explicitly requested one-copy lifetime.
        if (SecretPasteSignal.matches(value, event.fromIndex, event.addedCount, event.text, event.isPassword)) expire()
    }

    private fun expire() {
        lease = lease?.copy(deadline = SystemClock.elapsedRealtime()) ?: return
        pasteValue = null
        detectPaste = false
        persist()
        monitoringChanged?.invoke()
        reconcile()
    }

    fun reconcile() {
        val app = application ?: return
        val current = lease ?: return
        if (!current.expired(SystemClock.elapsedRealtime(), boot())) { schedule(); return }
        pasteValue = null
        if (detectPaste) { detectPaste = false; monitoringChanged?.invoke() }
        foregroundActivity.get()?.let { if (clearFromFocusedActivity(it)) return }
        // Background clipboard reads return null on modern Android. Request a short,
        // visible focus window, then verify the ownership token before clearing anything.
        showCleanupNotification(app)
        if (app.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val now = SystemClock.elapsedRealtime()
        if (lastFocusRequest == Long.MIN_VALUE || now - lastFocusRequest >= 5000L) {
            lastFocusRequest = now
            try { requestFocus?.invoke() } catch (_: RuntimeException) { /* Notification remains available. */ }
        }
    }

    fun clearFromFocusedActivity(activity: Activity): Boolean {
        val current = lease ?: return true
        if (!current.expired(SystemClock.elapsedRealtime(), boot())) return true
        if (!activity.hasWindowFocus() || activity.getSystemService(KeyguardManager::class.java).isDeviceLocked) return false
        return try {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            // Metadata is enough to check ownership; never read unrelated clipboard text.
            if (current.owns(clipboard.primaryClipDescription?.extras?.getString(SECRET_CLIP_TOKEN))) clipboard.clearPrimaryClip()
            forget()
            true
        } catch (_: RuntimeException) { false }
    }

    private fun forget() {
        lease = null
        pasteValue = null
        detectPaste = false
        handler.removeCallbacks(due)
        application?.getSystemService(AlarmManager::class.java)?.cancel(alarm)
        application?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        application?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        monitoringChanged?.invoke()
    }

    private fun persist() {
        val current = lease ?: return
        application?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("token", current.token)?.putLong("copiedAt", current.copiedAt)
            ?.putLong("deadline", current.deadline)?.putInt("boot", current.boot)?.apply()
    }

    private fun boot() = application?.let { Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT, -1) } ?: -1

    private fun schedule() {
        val app = application ?: return
        handler.removeCallbacks(due)
        val alarms = app.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarm)
        val current = lease ?: return
        val remaining = if (current.expired(SystemClock.elapsedRealtime(), boot())) 0L else current.deadline - SystemClock.elapsedRealtime()
        handler.postDelayed(due, remaining.coerceAtLeast(0))
        if (remaining > 0) try {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, current.deadline, "Password clipboard expiry", alarm, handler)
        } catch (_: SecurityException) { /* Handler and foreground reconciliation remain active. */ }
    }

    private fun showCleanupNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Password clipboard cleanup", NotificationManager.IMPORTANCE_LOW))
        val action = PendingIntent.getActivity(context, NOTIFICATION, ClipboardCleanupActivity.openIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Clear copied password")
                .setContentText("Tap to finish clipboard cleanup if Android prevents automatic cleanup.")
                .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (_: SecurityException) { /* Reconcile when Install Lock next regains focus. */ }
    }
}
