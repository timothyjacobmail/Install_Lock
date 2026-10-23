package com.example.installlock

internal data class FocusSession(
    val untilWall: Long = 0,
    val untilElapsed: Long = 0,
    val boot: Int = -1,
    val duration: Long = 0
)

/** Monotonic within a boot; automatic device time carries the deadline across reboot. */
internal object FocusLock {
    const val MAX_MINUTES = 24 * 60

    fun remaining(s: FocusSession, now: WatchdogClock): Long =
        (if (s.boot == now.boot) s.untilElapsed - now.elapsed else s.untilWall - now.wall)
            .coerceIn(0, s.duration.coerceAtLeast(0))

    fun start(old: FocusSession, minutes: Int, now: WatchdogClock): FocusSession? {
        if (minutes !in 1..MAX_MINUTES || remaining(old, now) > 0) return null
        val duration = minutes * 60_000L
        return FocusSession(now.wall + duration, now.elapsed + duration, now.boot, duration)
    }

    fun reconcile(s: FocusSession, now: WatchdogClock): FocusSession {
        val left = remaining(s, now)
        return when {
            left == 0L -> FocusSession()
            s.boot == now.boot -> s
            else -> s.copy(untilWall = now.wall + left, untilElapsed = now.elapsed + left, boot = now.boot)
        }
    }
}
