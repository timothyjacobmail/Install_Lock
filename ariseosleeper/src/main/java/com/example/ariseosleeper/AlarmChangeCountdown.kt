package com.example.ariseosleeper

/** Each settings request starts its own full wait; reboot restarts an active wait. */
internal data class AlarmChangeCountdown(val dueElapsed: Long, val boot: Int, val duration: Long) {
    fun reconcile(now: AlarmClockSnapshot): AlarmChangeCountdown =
        if (boot == now.boot) this else copy(dueElapsed = now.elapsed + duration, boot = now.boot)

    fun remaining(now: AlarmClockSnapshot): Long =
        (reconcile(now).dueElapsed - now.elapsed).coerceAtLeast(0)

    companion object {
        fun start(delayMinutes: Int, now: AlarmClockSnapshot): AlarmChangeCountdown {
            require(delayMinutes > 0)
            val duration = delayMinutes * 60_000L
            return AlarmChangeCountdown(now.elapsed + duration, now.boot, duration)
        }
    }
}
