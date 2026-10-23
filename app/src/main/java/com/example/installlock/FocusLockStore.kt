package com.example.installlock

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager

internal object FocusLockStore {
    private fun prefs(c: Context) = c.getSharedPreferences("focus_lock", Context.MODE_PRIVATE)
    private fun read(c: Context) = prefs(c).let {
        FocusSession(it.getLong("wall", 0), it.getLong("elapsed", 0),
            it.getInt("boot", -1), it.getLong("duration", 0))
    }
    private fun editor(c: Context, s: FocusSession) = prefs(c).edit()
        .putLong("wall", s.untilWall).putLong("elapsed", s.untilElapsed)
        .putInt("boot", s.boot).putLong("duration", s.duration)

    fun targets(c: Context): Set<String> = prefs(c).getStringSet("targets", emptySet()).orEmpty().toSet()
    // Retained after expiry, including across subsequent sessions, so system apps are released/retried.
    fun managed(c: Context): Set<String> = prefs(c).getStringSet("managed", emptySet()).orEmpty().toSet()

    fun excluded(c: Context): Set<String> = setOfNotNull(
        c.packageName, "com.android.settings",
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName,
        c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    )

    fun defaults(c: Context): Set<String> =
        (Store.protectedApps(c) + WatchdogStore.monitoredPackages(c)) - excluded(c)

    @Synchronized
    fun remaining(c: Context, now: WatchdogClock = WatchdogStore.clock(c)): Long {
        val old = read(c)
        val next = FocusLock.reconcile(old, now)
        if (next != old) check(editor(c, next).commit())
        return FocusLock.remaining(next, now)
    }

    /** No cancel, edit or credential override. Only an expired session can be replaced. */
    @Synchronized
    fun start(c: Context, minutes: Int, selected: Set<String>): Boolean {
        if (!Policy.isOwner(c)) return false
        val targets = selected - excluded(c)
        if (targets.isEmpty()) return false
        val next = FocusLock.start(read(c), minutes, WatchdogStore.clock(c)) ?: return false
        Policy.apply(c)
        return editor(c, next).putStringSet("targets", targets)
            .putStringSet("managed", managed(c) + targets).commit()
    }
}
