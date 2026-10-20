package com.example.delayedsecrect

import android.content.Context
import android.os.SystemClock

data class PendingDelayChange(
    val delayMinutes: Int,
    val effectiveAtElapsedMs: Long,
    val effectiveAtWallClockMs: Long,
    val bootReferenceMs: Long
)

data class PendingVisibleChange(
    val visibleSeconds: Int,
    val effectiveAtElapsedMs: Long,
    val effectiveAtWallClockMs: Long,
    val bootReferenceMs: Long
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        const val CHANGE_DELAY_MS = 30 * 60 * 1000L // decreases / visible changes take effect 30 min later
    }

    init {
        reconcilePending()
    }

    fun getDelayMinutes(): Int = prefs.getInt("delay_minutes", 15)
    fun getVisibleSeconds(): Int = prefs.getInt("visible_seconds", 60)

    // ---- Delay minutes ----

    fun getPendingDelay(): PendingDelayChange? {
        if (!prefs.contains("pending_delay_minutes")) return null
        return PendingDelayChange(
            delayMinutes = prefs.getInt("pending_delay_minutes", getDelayMinutes()),
            effectiveAtElapsedMs = prefs.getLong("pending_delay_effective_elapsed", 0L),
            effectiveAtWallClockMs = prefs.getLong("pending_delay_effective_wallclock", 0L),
            bootReferenceMs = prefs.getLong("pending_delay_boot_reference", 0L)
        )
    }

    private fun queuePendingDelay(delayMinutes: Int) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        prefs.edit()
            .putInt("pending_delay_minutes", delayMinutes)
            .putLong("pending_delay_effective_elapsed", nowElapsed + CHANGE_DELAY_MS)
            .putLong("pending_delay_effective_wallclock", nowWallClock + CHANGE_DELAY_MS)
            .putLong("pending_delay_boot_reference", nowWallClock - nowElapsed)
            .apply()
    }

    fun cancelPendingDelay() {
        prefs.edit()
            .remove("pending_delay_minutes")
            .remove("pending_delay_effective_elapsed")
            .remove("pending_delay_effective_wallclock")
            .remove("pending_delay_boot_reference")
            .apply()
    }

    // ---- Visible seconds ----

    fun getPendingVisible(): PendingVisibleChange? {
        if (!prefs.contains("pending_visible_seconds")) return null
        return PendingVisibleChange(
            visibleSeconds = prefs.getInt("pending_visible_seconds", getVisibleSeconds()),
            effectiveAtElapsedMs = prefs.getLong("pending_visible_effective_elapsed", 0L),
            effectiveAtWallClockMs = prefs.getLong("pending_visible_effective_wallclock", 0L),
            bootReferenceMs = prefs.getLong("pending_visible_boot_reference", 0L)
        )
    }

    private fun queuePendingVisible(visibleSeconds: Int) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        prefs.edit()
            .putInt("pending_visible_seconds", visibleSeconds)
            .putLong("pending_visible_effective_elapsed", nowElapsed + CHANGE_DELAY_MS)
            .putLong("pending_visible_effective_wallclock", nowWallClock + CHANGE_DELAY_MS)
            .putLong("pending_visible_boot_reference", nowWallClock - nowElapsed)
            .apply()
    }

    fun cancelPendingVisible() {
        prefs.edit()
            .remove("pending_visible_seconds")
            .remove("pending_visible_effective_elapsed")
            .remove("pending_visible_effective_wallclock")
            .remove("pending_visible_boot_reference")
            .apply()
    }

    // ---- Entry point used by the Settings screen ----

    /** Delay: applied immediately if increased/unchanged; queued for CHANGE_DELAY_MS if decreased.
     *  Visible seconds: always queued for CHANGE_DELAY_MS, either direction (unchanged behavior). */
    fun requestChange(delayMinutes: Int, visibleSeconds: Int) {
        val currentDelay = getDelayMinutes()
        if (delayMinutes >= currentDelay) {
            prefs.edit().putInt("delay_minutes", delayMinutes).apply()
            cancelPendingDelay() // in case a decrease was previously queued and got superseded
        } else {
            queuePendingDelay(delayMinutes)
        }

        if (visibleSeconds != getVisibleSeconds()) {
            queuePendingVisible(visibleSeconds)
        }
    }

    // ---- Reconciliation (reboot/clock-change safe, same pattern as RevealTimer) ----

    private fun rebootedSince(bootReferenceMs: Long): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        val currentBootReference = nowWallClock - nowElapsed
        return kotlin.math.abs(currentBootReference - bootReferenceMs) > 3000
    }

    private fun effectiveElapsedFor(effectiveAtElapsedMs: Long, effectiveAtWallClockMs: Long, bootReferenceMs: Long): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        return if (!rebootedSince(bootReferenceMs)) effectiveAtElapsedMs
        else nowElapsed + (effectiveAtWallClockMs - nowWallClock)
    }

    fun reconcilePending() {
        getPendingDelay()?.let { pending ->
            val effective = effectiveElapsedFor(pending.effectiveAtElapsedMs, pending.effectiveAtWallClockMs, pending.bootReferenceMs)
            if (SystemClock.elapsedRealtime() >= effective) {
                prefs.edit().putInt("delay_minutes", pending.delayMinutes).apply()
                cancelPendingDelay()
            }
        }
        getPendingVisible()?.let { pending ->
            val effective = effectiveElapsedFor(pending.effectiveAtElapsedMs, pending.effectiveAtWallClockMs, pending.bootReferenceMs)
            if (SystemClock.elapsedRealtime() >= effective) {
                prefs.edit().putInt("visible_seconds", pending.visibleSeconds).apply()
                cancelPendingVisible()
            }
        }
    }

    fun pendingDelayRemainingMs(): Long? {
        val pending = getPendingDelay() ?: return null
        val effective = effectiveElapsedFor(pending.effectiveAtElapsedMs, pending.effectiveAtWallClockMs, pending.bootReferenceMs)
        return (effective - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    fun pendingVisibleRemainingMs(): Long? {
        val pending = getPendingVisible() ?: return null
        val effective = effectiveElapsedFor(pending.effectiveAtElapsedMs, pending.effectiveAtWallClockMs, pending.bootReferenceMs)
        return (effective - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }
}