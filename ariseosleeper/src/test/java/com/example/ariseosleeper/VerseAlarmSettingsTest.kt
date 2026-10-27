package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class VerseAlarmSettingsTest {
    private val now = AlarmClockSnapshot(1_000_000, 10_000, 8)
    private val verse = AlarmVerse("v", "Test", "Original test passage.")
    private val enabled = VerseAlarmConfig(true)
    private val off = VerseAlarmConfig(false)
    private val idle = VerseAlarmState(config = enabled, verses = listOf(verse), everArmed = true)
    private val check = VerseChallenge("c", "v", 0, false, now.wall, now.elapsed, now.boot)

    @Test fun idleEditsApplyImmediatelyAfterPreviousActivation() {
        val changed = idle.requestConfig(VerseAlarmConfig(true, 8, 30, 4), now)
        assertEquals(8, changed.config.hour)
        assertEquals(30, changed.config.minute)
        assertEquals(4, changed.config.memoryAfter)
        assertTrue(changed.nextAlarm > now.wall)
        assertNull(changed.pending)
        assertNull(changed.countdown)
        val disabled = changed.requestConfig(off, now)
        assertFalse(disabled.config.enabled)
        assertEquals(0L, disabled.nextAlarm)
    }

    @Test fun activeRoutineRequiresDelayAndKeepsItsCurrentCheck() {
        val active = idle.copy(challenge = check).requestConfig(off, now)
        assertEquals(enabled, active.config)
        assertEquals(check, active.challenge)
        assertEquals(off, active.pending)
        assertEquals(900_000L, active.countdown!!.remaining(now))
        assertEquals(active, active.applyPendingIfIdle(now.copy(elapsed = now.elapsed + 900_000)))
    }

    @Test fun quietMinutesAndFollowUpWaitsStillCountAsAnActiveRoutine() {
        for (challenge in listOf(check.copy(quietUsed = true), check.copy(round = 1, waiting = true),
            check.copy(round = 2, waiting = true))) {
            val changed = idle.copy(challenge = challenge).requestConfig(off, now)
            assertTrue(changed.config.enabled)
            assertNotNull(changed.countdown)
        }
    }

    @Test fun remainingWaitDisappearsAsSoonAsRoutineFinishes() {
        val active = idle.copy(challenge = check).requestConfig(off, now)
        val completed = active.copy(challenge = null).applyPendingIfIdle(now.copy(elapsed = now.elapsed + 600_000))
        assertFalse(completed.config.enabled)
        assertNull(completed.pending)
        assertNull(completed.countdown)
    }

    @Test fun existingIdleQueueFromOlderVersionsAppliesWithoutAWait() {
        val old = idle.copy(pending = off, countdown = AlarmChangeCountdown.start(15, now))
        val migrated = old.applyPendingIfIdle(now)
        assertFalse(migrated.config.enabled)
        assertNull(migrated.countdown)
        assertNull(migrated.pending)
    }
}
