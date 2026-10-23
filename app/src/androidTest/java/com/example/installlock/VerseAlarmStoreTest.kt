package com.example.installlock

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class VerseAlarmStoreTest {
    private val c = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = WatchdogClock(1_000_000, 10_000, 8)
    private val text = "Wake up and open the window to begin a new day."
    private var original: String? = null
    private val prefs get() = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
    @Before fun setup() {
        original = prefs.getString("state", null)
        prefs.edit().clear().commit()
        VerseAlarmStore.addVerse(c, "Original test prose", text)
    }
    @After fun restore() { prefs.edit().clear().putString("state", original).commit() }

    private fun ring(): Pair<WatchdogClock, VerseChallenge> {
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
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), start)
        VerseAlarmStore.saveConfig(c, VerseAlarmConfig(false), start)
        val shifted = start.copy(wall = start.wall + 100_000, elapsed = start.elapsed + 30_000)
        assertTrue(VerseAlarmStore.reconcile(c, shifted).config.enabled)
        val boot = shifted.copy(elapsed = 1000, boot = 9)
        assertEquals(900_000L, VerseAlarmStore.reconcile(c, boot).countdown!!.remaining(boot))
    }
}
