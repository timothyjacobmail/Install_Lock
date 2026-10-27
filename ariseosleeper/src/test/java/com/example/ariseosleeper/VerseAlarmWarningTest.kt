package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class VerseAlarmWarningTest {
    private val now = AlarmClockSnapshot(1_000_000, 10_000, 8)
    private val check = VerseChallenge("c", "v", 1, true, now.wall + 300_000,
        now.elapsed + 300_000, now.boot, waiting = true)
    private fun after(ms: Long) = now.copy(wall = now.wall + ms, elapsed = now.elapsed + ms)

    @Test fun entryOpensExactlyOneMinuteBeforeTheAlarm() {
        assertFalse(check.canAnswer(after(239_999)))
        assertFalse(check.warning(after(239_999)))
        assertTrue(check.canAnswer(after(240_000)))
        assertTrue(check.warning(after(240_000)))
        assertFalse(check.ringing(after(240_000)))
        assertTrue(check.warning(after(299_999)))
        assertTrue(check.ringing(after(300_000)))
        assertFalse(check.warning(after(300_000)))
        assertTrue(check.canAnswer(after(300_000)))
    }

    @Test fun warningScheduleSupportsTheDailyAlarmAndEachFollowUp() {
        val daily = VerseAlarmState(config = VerseAlarmConfig(true), nextAlarm = now.wall + 3_600_000)
        assertEquals(now.wall + 3_540_000, daily.nextWarningAt(now))
        for (round in 0..2) {
            assertEquals(now.wall + 240_000, daily.copy(challenge = check.copy(round = round)).nextWarningAt(now))
        }
    }

    @Test fun deliveredWarningsQuietMinutesAndFinishedRoutinesDoNotScheduleAnotherChime() {
        val state = VerseAlarmState(challenge = check)
        assertNull(state.copy(challenge = check.copy(warningSent = true)).nextWarningAt(now))
        assertNull(state.copy(challenge = check.copy(waiting = false, quietUsed = true)).nextWarningAt(now))
        assertNull(state.copy(challenge = null).nextWarningAt(now))
        assertNull(state.nextWarningAt(after(300_000)))
    }

    @Test fun lateRecoveryUsesOnlyTheRemainingWarningWindow() {
        val late = after(280_000)
        assertEquals(late.wall + 1000, VerseAlarmState(challenge = check).nextWarningAt(late))
        assertEquals(20_000L, check.remaining(late))
    }

    @Test fun clockChangesDoNotOpenTheWarningEarlyWithinTheSameBoot() {
        assertFalse(check.warning(now.copy(wall = Long.MAX_VALUE)))
        assertFalse(check.canAnswer(now.copy(wall = Long.MAX_VALUE)))
        assertTrue(check.warning(after(240_000).copy(wall = 0)))
        assertEquals(60_000L, check.remaining(now.copy(wall = check.dueWall - 60_000, elapsed = 1000, boot = 9)))
    }
}
