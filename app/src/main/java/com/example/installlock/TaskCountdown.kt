package com.example.installlock

/** A confirmation starts its own full wait, independent of any earlier password reveal. */
internal data class TaskCountdown(val dueElapsed: Long, val boot: Int, val duration: Long) {
    fun reconcile(now: WatchdogClock): TaskCountdown =
        if (boot == now.boot) this else copy(dueElapsed = now.elapsed + duration, boot = now.boot)

    fun remaining(now: WatchdogClock): Long =
        (reconcile(now).dueElapsed - now.elapsed).coerceAtLeast(0)

    companion object {
        fun start(delayMinutes: Int, now: WatchdogClock): TaskCountdown {
            require(delayMinutes > 0)
            val duration = delayMinutes * 60_000L
            return TaskCountdown(now.elapsed + duration, now.boot, duration)
        }
    }
}
