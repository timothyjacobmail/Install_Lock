package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class FocusLockTest {
    private val clock = WatchdogClock(1_800_000_000_000, 10_000, 5)

    @Test fun durationBoundsAndExactExpiry() {
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 1441, Int.MAX_VALUE)) {
            assertNull(FocusLock.start(FocusSession(), minutes, clock))
        }
        val session = FocusLock.start(FocusSession(), 1, clock)!!
        assertEquals(60_000L, FocusLock.remaining(session, clock))
        assertEquals(1L, FocusLock.remaining(session, clock.copy(elapsed = clock.elapsed + 59_999)))
        assertEquals(0L, FocusLock.remaining(session, clock.copy(elapsed = clock.elapsed + 60_000)))
        assertEquals(86_400_000L, FocusLock.remaining(FocusLock.start(FocusSession(), 1440, clock)!!, clock))
    }

    @Test fun runningSessionCannotBeShortenedOrReplacedOrExtended() {
        val session = FocusLock.start(FocusSession(), 30, clock)!!
        for (minutes in listOf(1, 30, 1440)) assertNull(FocusLock.start(session, minutes, clock))
        assertNotNull(FocusLock.start(session, 10, clock.copy(elapsed = clock.elapsed + 30 * 60_000)))
    }

    @Test fun wallClockChangesCannotEndOrExtendSessionWithinSameBoot() {
        val session = FocusLock.start(FocusSession(), 30, clock)!!
        val later = clock.copy(elapsed = clock.elapsed + 60_000)
        assertEquals(29 * 60_000L, FocusLock.remaining(session, later.copy(wall = clock.wall + 86_400_000)))
        assertEquals(29 * 60_000L, FocusLock.remaining(session, later.copy(wall = clock.wall - 86_400_000)))
    }

    @Test fun rebootCountsPoweredOffTimeAndRebasesOntoMonotonicClock() {
        val session = FocusLock.start(FocusSession(), 30, clock)!!
        val reboot = WatchdogClock(clock.wall + 10 * 60_000, 2_000, clock.boot + 1)
        val restored = FocusLock.reconcile(session, reboot)
        assertEquals(20 * 60_000L, FocusLock.remaining(restored, reboot))
        assertEquals(19 * 60_000L, FocusLock.remaining(restored,
            reboot.copy(wall = clock.wall + 86_400_000, elapsed = reboot.elapsed + 60_000)))
        assertEquals(FocusSession(), FocusLock.reconcile(session, reboot.copy(wall = session.untilWall)))
    }

    @Test fun focusLockIsIndependentOfManualUnlocksAndOtherReasonsRemainAfterExpiry() {
        val focusOnly = AppLockReasons(focusUntil = clock.wall + 60_000)
        assertTrue(focusOnly.shouldLock)
        assertTrue(focusOnly.descriptions().single().contains("cannot be cancelled"))
        assertFalse(focusOnly.copy(focusUntil = null).shouldLock)
        assertTrue(focusOnly.copy(appLock = true, focusUntil = null).shouldLock)
        assertTrue(focusOnly.copy(curfewUntil = clock.wall + 60_000, focusUntil = null).shouldLock)
        assertTrue(focusOnly.copy(categoryCooldownUntil = clock.wall + 60_000, focusUntil = null).shouldLock)
    }
}
