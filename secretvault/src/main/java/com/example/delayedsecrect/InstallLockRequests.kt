package com.example.delayedsecrect

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject

internal data class InstallLockRequest(
    val id: String, val scope: String, val title: String,
    val secretId: String = "", val cycleId: String = "", val notified: Boolean = false
) {
    fun encode() = JSONObject().put("id", id).put("scope", scope).put("title", title)
        .put("secret", secretId).put("cycle", cycleId).put("notified", notified).toString()
    companion object {
        fun decode(raw: String): InstallLockRequest? = try {
            JSONObject(raw).let { InstallLockRequest(it.getString("id"), it.getString("scope"), it.getString("title"),
                it.optString("secret"), it.optString("cycle"), it.optBoolean("notified")) }
        } catch (_: Exception) { null }
    }
}

/** Request metadata only. Secret values remain in SecretStore until the user confirms delivery. */
internal class InstallLockRequests(private val c: Context) {
    companion object {
        const val REQUEST = "com.example.delayedsecrect.REQUEST_INSTALL_LOCK"
        const val RESULT = "com.example.installlock.DELAYED_PASSWORD"
        private val mutex = Any()
    }
    private val prefs = c.getSharedPreferences("install_lock_requests", Context.MODE_PRIVATE)
    private val alarms = c.getSystemService(AlarmManager::class.java)
    private val timerStore = TimerStore(c)
    private val secretStore = SecretStore(c)

    fun all() = prefs.all.filterKeys { it.startsWith("request.") }.values.filterIsInstance<String>().mapNotNull(InstallLockRequest::decode)
    fun get(id: String) = prefs.getString("request.$id", null)?.let(InstallLockRequest::decode)
    private fun save(request: InstallLockRequest) = check(prefs.edit().putString("request.${request.id}", request.encode()).commit())
    fun suggestedSecret(scope: String) = prefs.getString("link.$scope", null)
    fun receive(id: String, scope: String, title: String): InstallLockRequest = synchronized(mutex) {
        require(Regex("[0-9a-fA-F-]{36}").matches(id) && scope.length in 1..250 && title.length in 1..16000)
        get(id)?.let { return it }
        all().filter { it.scope == scope }.forEach { cancel(it.id) }
        return InstallLockRequest(id, scope, title).also(::save)
    }

    fun timer(secret: Secret): RevealTimer {
        val settings = SettingsStore(c)
        return RevealTimer(secret.id, (secret.delayMinutes ?: settings.getDelayMinutes()) * 60_000L,
            settings.getVisibleSeconds() * 1000L, timerStore)
    }

    fun select(id: String, secretId: String): Boolean = synchronized(mutex) {
        val request = get(id) ?: return false
        val secret = secretStore.getAll().firstOrNull { it.id == secretId } ?: return false
        val timer = timer(secret)
        val state = timer.currentState(claim = false).state
        if (state == RevealState.HARD_LOCKED) return false
        if (state == RevealState.LOCKED) timer.startCountdown()
        val record = timerStore.get(secret.id) ?: return false
        val cycle = record.cycleId.ifEmpty { "legacy:${record.revealAtWallClockMs}" }
        save(request.copy(secretId = secretId, cycleId = cycle, notified = false))
        prefs.edit().putString("link.${request.scope}", secretId).apply()
        reconcile(id)
        return true
    }

    /** null means deleted, cancelled, expired, hard locked, or a different reveal cycle. */
    fun availability(request: InstallLockRequest): Pair<Secret, TimerInfo>? {
        val secret = secretStore.getAll().firstOrNull { it.id == request.secretId } ?: return null
        val record = timerStore.get(secret.id) ?: return null
        if (!record.started || record.cycleId.ifEmpty { "legacy:${record.revealAtWallClockMs}" } != request.cycleId) return null
        val info = timer(secret).currentState(claim = false)
        if (info.state != RevealState.COUNTING_DOWN && info.state != RevealState.REVEALED) return null
        return secret to info
    }

    fun exactAlarmsAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun alarm(id: String) = PendingIntent.getBroadcast(c, 0,
        Intent(c, InstallLockReadyReceiver::class.java).setData(Uri.parse("delayed-secret://install-lock/$id"))
            .putExtra("request_id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun reconcile(id: String): Unit = synchronized(mutex) {
        val request = get(id) ?: return
        if (request.secretId.isEmpty()) return
        val (_, info) = availability(request) ?: run { cancel(id); return }
        val now = SystemClock.elapsedRealtime()
        val next = if (info.state == RevealState.COUNTING_DOWN) info.revealAtElapsedMs
            else if (info.hideAtElapsedMs > 0) info.hideAtElapsedMs else info.revealAtElapsedMs + CLAIM_WINDOW_MS
        if (info.state == RevealState.REVEALED && !request.notified) {
            if (showReady(request, next - now)) save(request.copy(notified = true))
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, alarm(id))
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, alarm(id))
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, alarm(id))
        }
    }

    fun reconcileAll() { all().forEach { reconcile(it.id) } }

    fun cancel(id: String): Unit = synchronized(mutex) {
        // Cancels delivery, not a user's separate manual reveal of that secret.
        check(prefs.edit().remove("request.$id").commit())
        alarms.cancel(alarm(id))
        c.getSystemService(NotificationManager::class.java).cancel(id, 2)
    }

    private fun showReady(request: InstallLockRequest, expiresIn: Long): Boolean {
        createNotificationChannel(c)
        val open = PendingIntent.getActivity(c, 0, openIntent(c, request.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Install Lock password ready")
            .setContentText("${request.title}. Tap to confirm unlock.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${request.title}\nTap to confirm unlock before this request expires."))
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(expiresIn.coerceAtLeast(1))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        val allowed = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) c.getSystemService(NotificationManager::class.java).notify(request.id, 2, notification)
        return allowed
    }

    fun deliver(id: String): Boolean = synchronized(mutex) {
        val request = get(id) ?: return false
        val (secret, state) = availability(request) ?: run { cancel(id); return false }
        if (state.state != RevealState.REVEALED) return false
        val reveal = timer(secret)
        val claimed = reveal.currentState()
        if (claimed.state != RevealState.REVEALED || claimed.hideAtElapsedMs <= SystemClock.elapsedRealtime()) return false
        val intent = Intent(RESULT).setComponent(ComponentName(c.packageName, "com.example.installlock.DelayedUnlockActivity"))
            .putExtra("request_id", id).putExtra("password", secret.value)
            .putExtra("expires_elapsed", claimed.hideAtElapsedMs)
            .putExtra("boot_count", Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1))
        // Sending starts an activity only in response to the user's foreground confirmation.
        c.startActivity(intent)
        reveal.closeEarly()
        cancel(id)
        return true
    }

    /** Show, copy and unlock share one claim deadline; repeated claims never extend it. */
    fun claimInline(id: String): InlineSecretClaim? = synchronized(mutex) {
        val request = get(id) ?: return null
        val (secret, state) = availability(request) ?: return null
        if (state.state != RevealState.REVEALED) return null
        val claimed = timer(secret).currentState()
        if (claimed.state != RevealState.REVEALED || claimed.hideAtElapsedMs <= SystemClock.elapsedRealtime()) return null
        reconcile(id)
        return InlineSecretClaim(secret.value, claimed.hideAtElapsedMs)
    }

    fun completeInline(id: String): Unit = synchronized(mutex) {
        get(id)?.let { availability(it)?.first }?.let { timer(it).closeEarly() }
        cancel(id)
    }

    fun openIntent(context: Context, id: String) = Intent("com.example.installlock.OPEN_DELAYED_SECRET")
        .setComponent(ComponentName(context.packageName, "com.example.installlock.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .setData(Uri.parse("delayed-secret://install-lock/$id")).putExtra("request_id", id)
}

class InstallLockReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { intent.getStringExtra("request_id")?.let { InstallLockRequests(context).reconcile(it) } }
            catch (_: Exception) { /* The user can still inspect the persisted request in the app. */ }
            finally { pending.finish() }
        }.start()
    }
}

class InstallLockRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        Thread {
            try { InstallLockRequests(context).reconcileAll() }
            catch (_: Exception) { /* Reschedule on the next foreground launch. */ }
            finally { pending.finish() }
        }.start()
    }
}
