package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class AlarmChangeCountdownTest {
    private val start = AlarmClockSnapshot(1_000_000L, 10_000L, 4)

    @Test fun twoMinuteDelayBecomesDueExactlyTwoMinutesAfterConfirmation() {
        val timer = AlarmChangeCountdown.start(2, start)
        assertEquals(120_000L, timer.remaining(start))
        assertEquals(1L, timer.remaining(start.copy(elapsed = 129_999L)))
        assertEquals(0L, timer.remaining(start.copy(elapsed = 130_000L)))
        assertEquals(0L, timer.remaining(start.copy(elapsed = 140_000L)))
    }

    @Test fun wallClockChangesCannotShortenOrExtendTheWait() {
        val timer = AlarmChangeCountdown.start(2, start)
        assertEquals(60_000L, timer.remaining(start.copy(wall = Long.MAX_VALUE, elapsed = 70_000L)))
        assertEquals(60_000L, timer.remaining(start.copy(wall = 0, elapsed = 70_000L)))
    }

    @Test fun rebootRestartsTheWaitOnceAndProcessRestartKeepsItsDeadline() {
        val timer = AlarmChangeCountdown.start(2, start)
        val boot = start.copy(wall = 9_000_000L, elapsed = 1000L, boot = 5)
        val restored = timer.reconcile(boot)
        assertEquals(120_000L, restored.remaining(boot))
        assertEquals(restored, restored.reconcile(boot.copy(elapsed = 31_000L)))
        assertEquals(90_000L, restored.remaining(boot.copy(elapsed = 31_000L)))
    }

    @Test fun separateConfirmationsHaveIndependentFullWaits() {
        val first = AlarmChangeCountdown.start(2, start)
        val later = start.copy(elapsed = start.elapsed + 60_000L)
        val second = AlarmChangeCountdown.start(2, later)
        assertEquals(60_000L, first.remaining(later))
        assertEquals(120_000L, second.remaining(later))
    }
}
