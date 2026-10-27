package com.example.ariseosleeper

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class VerseAlarmStoreTest {
    private val c = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = AlarmClockSnapshot(1_000_000, 10_000, 8)
    private val text = "Wake up and open the window to begin a new day."
    private var original: String? = null
    private val prefs get() = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
    @Before fun setup() {
        original = prefs.getString("state", null)
        prefs.edit().clear().commit()
        VerseAlarmStore.addVerse(c, "Original test prose", text)
    }
    @After fun restore() { prefs.edit().clear().putString("state", original).commit() }

    private fun ring(): Pair<AlarmClockSnapshot, VerseChallenge> {
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true, 7, 0, 2), start)
        val due = VerseAlarmStore.read(c).nextAlarm
        val now = start.copy(wall = due, elapsed = start.elapsed + due - start.wall)
        return now to VerseAlarmStore.reconcile(c, now).challenge!!
    }

    @Test fun successfulAnswersAdvanceOnlyOnceAndRequireTheFollowUpWait() {
        val (now, ch) = ring()
        assertFalse(ch.fromMemory)
        assertFalse(VerseAlarmStore.answer(c, ch.id, "wrong", false, now))
        assertTrue(VerseAlarmStore.answer(c, ch.id, text, false, now))
        assertFalse(VerseAlarmStore.answer(c, ch.id, text, false, now))
        val second = VerseAlarmStore.read(c).challenge!!
        assertFalse(VerseAlarmStore.answer(c, second.id, text, false, now))
        val later = now.copy(wall = now.wall + 300_000, elapsed = now.elapsed + 300_000)
        assertTrue(VerseAlarmStore.answer(c, second.id, text, false, later))
        val third = VerseAlarmStore.read(c).challenge!!
        assertTrue(third.fromMemory)
        assertEquals(2, VerseAlarmStore.read(c).verses.single().successes)
        assertTrue(VerseAlarmStore.answer(c, third.id, text, false,
            later.copy(wall = later.wall + 300_000, elapsed = later.elapsed + 300_000)))
        assertNull(VerseAlarmStore.read(c).challenge)
        assertEquals(3, VerseAlarmStore.read(c).verses.single().successes)
    }

    @Test fun quietTimeIsLimitedAndDoesNotAwardCredit() {
        val (now, ch) = ring()
        assertTrue(VerseAlarmStore.quiet(c, ch.id, now))
        assertFalse(VerseAlarmStore.quiet(c, ch.id, now))
        assertEquals(0, VerseAlarmStore.read(c).verses.single().successes)
        assertFalse(VerseAlarmStore.read(c).challenge!!.ringing(now))
        assertTrue(VerseAlarmStore.read(c).challenge!!.ringing(now.copy(elapsed = now.elapsed + 60_000)))
        assertTrue(VerseAlarmStore.answer(c, ch.id, text, false, now))
    }

    @Test fun addingPassagesWhileDailyAlarmIsEnabledPreservesItsSchedule() {
        val now = AlarmClock.now(c)
        val wakeTime = java.time.Instant.ofEpochMilli(now.wall).atZone(java.time.ZoneId.systemDefault()).plusHours(2)
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true, wakeTime.hour, wakeTime.minute, 4), now)
        val before = VerseAlarmStore.read(c)
        VerseAlarmStore.addVerse(c, "Another original passage", "Begin the day with a clear mind and a grateful heart.")
        val after = VerseAlarmStore.read(c)
        assertEquals(2, after.verses.size)
        assertEquals(before.config, after.config)
        assertEquals(before.nextAlarm, after.nextAlarm)
        assertEquals(before.verses.single(), after.verses.first())
        assertNull(after.challenge)
    }

    @Test fun addingPassagesDuringAnActiveRoutineIsRejected() {
        ring()
        val before = VerseAlarmStore.read(c).verses
        assertThrows(IllegalStateException::class.java) {
            VerseAlarmStore.addVerse(c, "New passage", "Begin the day with a clear mind.")
        }
        assertEquals(before, VerseAlarmStore.read(c).verses)
    }

    @Test fun dailyWarningIsOneMinuteEarlyAndCanCompleteTheFirstCheck() {
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), start)
        val due = VerseAlarmStore.read(c).nextAlarm
        val warningTime = start.copy(wall = due - 60_000, elapsed = start.elapsed + due - start.wall - 60_000)
        assertNull(VerseAlarmStore.reconcile(c, warningTime.copy(wall = warningTime.wall - 1,
            elapsed = warningTime.elapsed - 1)).challenge)
        val state = VerseAlarmStore.reconcile(c, warningTime)
        val ch = state.challenge!!
        assertTrue(ch.warning(warningTime))
        assertEquals(due, ch.dueWall)
        assertTrue(state.nextAlarm > due)
        assertNotNull(VerseAlarmStore.takeWarning(c, warningTime))
        assertNull(VerseAlarmStore.takeWarning(c, warningTime))
        assertTrue(VerseAlarmStore.read(c).challenge!!.warningSent)
        assertTrue(VerseAlarmStore.answer(c, ch.id, text, false, warningTime))
        assertFalse(VerseAlarmStore.answer(c, ch.id, text, false, warningTime))
        val next = VerseAlarmStore.read(c).challenge!!
        assertEquals(1, next.round)
        assertEquals(300_000L, next.remaining(warningTime))
        assertFalse(next.warningSent)
    }

    @Test fun followUpAcceptsAnAnswerOnlyInItsLastMinuteAndDoesNotExtendTheDeadline() {
        val (now, ch) = ring()
        VerseAlarmStore.answer(c, ch.id, text, false, now)
        val next = VerseAlarmStore.read(c).challenge!!
        val early = now.copy(wall = now.wall + 239_999, elapsed = now.elapsed + 239_999)
        assertFalse(VerseAlarmStore.answer(c, next.id, text, false, early))
        val warningTime = early.copy(wall = early.wall + 1, elapsed = early.elapsed + 1)
        assertNotNull(VerseAlarmStore.takeWarning(c, warningTime))
        assertEquals(next.dueElapsed, VerseAlarmStore.read(c).challenge!!.dueElapsed)
        assertFalse(VerseAlarmStore.quiet(c, next.id, warningTime))
        assertTrue(VerseAlarmStore.answer(c, next.id, text, false, warningTime))
    }

    @Test fun maturedDisableCannotCancelAnActiveRoutine() {
        val (now, ch) = ring()
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false), now)
        val later = now.copy(wall = now.wall + 900_000, elapsed = now.elapsed + 900_000)
        val active = VerseAlarmStore.reconcile(c, later)
        assertTrue(active.config.enabled)
        assertEquals(ch.id, active.challenge!!.id)
        assertNotNull(active.pending)
    }

    @Test fun rebootRestoresChallengeAndDoesNotResetLearning() {
        val (now, ch) = ring()
        VerseAlarmStore.answer(c, ch.id, text, false, now)
        val before = VerseAlarmStore.read(c)
        val boot = now.copy(wall = now.wall + 60_000, elapsed = 1000, boot = 9)
        val after = VerseAlarmStore.reconcile(c, boot)
        assertEquals(before.challenge!!.id, after.challenge!!.id)
        assertEquals(240_000L, after.challenge.remaining(boot))
        assertEquals(1, after.verses.single().successes)
    }

    @Test fun delayedSettingsCannotBeAcceleratedByWallTimeAndRestartWaitAfterBoot() {
        val (now, _) = ring()
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false), now)
        val shifted = now.copy(wall = now.wall + 100_000, elapsed = now.elapsed + 30_000)
        assertTrue(VerseAlarmStore.reconcile(c, shifted).config.enabled)
        val boot = shifted.copy(elapsed = 1000, boot = 9)
        assertEquals(900_000L, VerseAlarmStore.reconcile(c, boot).countdown!!.remaining(boot))
    }

    @Test fun idleAlterationsAreImmediateEvenAfterTheAlarmWasEnabledBefore() {
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), start)
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false), start)
        val off = VerseAlarmStore.read(c)
        assertFalse(off.config.enabled)
        assertNull(off.pending)
        assertNull(off.countdown)
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true, 8, 30, 4), start)
        assertEquals(VerseAlarmConfig(true, 8, 30, 4), VerseAlarmStore.read(c).config)
        assertNull(VerseAlarmStore.read(c).countdown)
    }

    @Test fun completingTheRoutineRemovesAnyRemainingAlterationWait() {
        var (now, ch) = ring()
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false), now)
        repeat(3) {
            assertTrue(VerseAlarmStore.answer(c, ch.id, text, false, now))
            if (it < 2) {
                ch = VerseAlarmStore.read(c).challenge!!
                now = now.copy(wall = now.wall + 300_000, elapsed = now.elapsed + 300_000)
            }
        }
        val done = VerseAlarmStore.read(c)
        assertNull(done.challenge)
        assertFalse(done.config.enabled)
        assertNull(done.pending)
        assertNull(done.countdown)
    }

    @Test fun oldSavedSettingsKeepTheirDefaults() {
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), start)
        val json = org.json.JSONObject(prefs.getString("state", null)!!)
        json.getJSONObject("config").apply { remove("sessions"); remove("additionalTimes") }
        json.getJSONArray("verses").getJSONObject(0).remove("quietSeconds")
        prefs.edit().putString("state", json.toString()).commit()
        val restored = VerseAlarmStore.read(c)
        assertEquals(3, restored.config.sessions)
        assertEquals(listOf(VerseAlarmTime(7, 0)), restored.config.times)
        assertEquals(60, restored.verses.single().quietSeconds)
    }

    @Test fun customQuietTimesPersistPerVerseAndSurviveReboot() {
        val first = VerseAlarmStore.read(c).verses.single().id
        VerseAlarmStore.setQuietTime(c, first, 900)
        val (now, ch) = ring()
        assertTrue(VerseAlarmStore.quiet(c, ch.id, now))
        assertEquals(900_000L, VerseAlarmStore.read(c).challenge!!.remaining(now))
        val boot = now.copy(wall = now.wall + 5000, elapsed = 1000, boot = 9)
        assertEquals(895_000L, VerseAlarmStore.reconcile(c, boot).challenge!!.remaining(boot))
        assertFalse(VerseAlarmStore.quiet(c, ch.id, boot))
        assertThrows(IllegalStateException::class.java) { VerseAlarmStore.setQuietTime(c, first, 60) }
    }

    @Test fun quietTimesBelongToIndividualVersesAndInvalidValuesAreRejected() {
        val first = VerseAlarmStore.read(c).verses.single()
        VerseAlarmStore.setQuietTime(c, first.id, 900)
        VerseAlarmStore.addVerse(c, "Another passage", "Begin today with a clear mind.", 30)
        for (seconds in listOf(0, -1, 3601)) {
            assertThrows(IllegalArgumentException::class.java) { VerseAlarmStore.setQuietTime(c, first.id, seconds) }
            assertThrows(IllegalArgumentException::class.java) {
                VerseAlarmStore.addVerse(c, "Other", "Another original test passage.", seconds)
            }
        }
        assertEquals(listOf(900, 30), VerseAlarmStore.read(c).verses.map { it.quietSeconds })
    }

    @Test fun configuredSessionCountControlsCompletionAndActiveChangesWait() {
        for (sessions in listOf(1, 5)) {
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true, sessions = sessions), start)
            val due = VerseAlarmStore.read(c).nextAlarm
            var now = start.copy(wall = due, elapsed = start.elapsed + due - start.wall)
            VerseAlarmStore.reconcile(c, now)
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false, sessions = 2,
                additionalTimes = listOf(VerseAlarmTime(18, 0))), now)
            assertEquals(sessions, VerseAlarmStore.read(c).config.sessions)
            assertEquals(2, VerseAlarmStore.read(c).pending!!.sessions)
            repeat(sessions) { index ->
                val ch = VerseAlarmStore.read(c).challenge!!
                assertEquals(index, ch.round)
                assertTrue(VerseAlarmStore.answer(c, ch.id, text, false, now))
                now = now.copy(wall = now.wall + 300_000, elapsed = now.elapsed + 300_000)
            }
            val done = VerseAlarmStore.read(c)
            assertNull(done.challenge)
            assertFalse(done.config.enabled)
            assertEquals(2, done.config.sessions)
            assertEquals(listOf(VerseAlarmTime(18, 0)), done.config.additionalTimes)
        }
    }

    @Test fun overlappingAlarmsRunInOrderWithoutLosingIntermediateTimes() {
        val config = VerseAlarmConfig(true, 7, 0, sessions = 1,
            additionalTimes = listOf(VerseAlarmTime(7, 1), VerseAlarmTime(7, 2)))
        VerseAlarmStore.saveConfig(c, config, start)
        assertEquals(config, VerseAlarmStore.read(c).config)
        val due = VerseAlarmStore.read(c).nextAlarm
        val now = start.copy(wall = due, elapsed = start.elapsed + due - start.wall)
        val first = VerseAlarmStore.reconcile(c, now).challenge!!
        val later = now.copy(wall = due + 600_000, elapsed = now.elapsed + 600_000)
        assertEquals(first.id, VerseAlarmStore.reconcile(c, later).challenge!!.id)
        assertTrue(VerseAlarmStore.answer(c, first.id, text, false, later))
        val second = VerseAlarmStore.reconcile(c, later).challenge!!
        assertEquals(due + 120_000, VerseAlarmStore.read(c).nextAlarm)
        assertTrue(second.ringing(later))
        assertTrue(VerseAlarmStore.answer(c, second.id, text, false, later))
        val third = VerseAlarmStore.reconcile(c, later).challenge!!
        assertTrue(VerseAlarmStore.answer(c, third.id, text, false, later))
        assertNull(VerseAlarmStore.reconcile(c, later).challenge)
        assertEquals(3, VerseAlarmStore.read(c).verses.single().successes)
        assertEquals(due + 86_400_000, VerseAlarmStore.read(c).nextAlarm)
    }
}
