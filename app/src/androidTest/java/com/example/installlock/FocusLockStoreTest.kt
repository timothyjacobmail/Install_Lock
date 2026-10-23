package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusLockStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("focus_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private fun freshContext() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun persistedTimerAndTargetsSurviveFreshReadsRebootAndExpiry() {
        val start = WatchdogClock(1_800_000_000_000, 10_000, 7)
        val session = FocusLock.start(FocusSession(), 30, start)!!
        val targets = setOf("test.distraction", "test.system.browser")
        assertTrue(prefs.edit().putLong("wall", session.untilWall).putLong("elapsed", session.untilElapsed)
            .putInt("boot", session.boot).putLong("duration", session.duration)
            .putStringSet("targets", targets).putStringSet("managed", targets).commit())
        assertEquals(30 * 60_000L, FocusLockStore.remaining(freshContext(), start))
        val reboot = WatchdogClock(start.wall + 10 * 60_000, 2_000, 8)
        assertEquals(20 * 60_000L, FocusLockStore.remaining(freshContext(), reboot))
        assertEquals(8, prefs.getInt("boot", -1))
        assertEquals(targets, FocusLockStore.targets(freshContext()))
        assertEquals(0L, FocusLockStore.remaining(freshContext(), reboot.copy(elapsed = 2_000 + 20 * 60_000)))
        // Policy must still visit system targets to release them, and retry failed releases.
        assertEquals(targets, FocusLockStore.managed(freshContext()))
        assertEquals(0L, prefs.getLong("wall", -1))
    }
}
