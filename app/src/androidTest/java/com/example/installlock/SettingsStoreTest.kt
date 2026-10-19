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
class SettingsStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("settings_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @After
    fun cleanUp() { prefs.edit().clear().commit() }

    @Test
    fun settingsPasswordAndFailedAttemptsAreIndependent() {
        Store.setPassword(context, "MainPass1", Store.Pw.MAIN)
        Store.setPassword(context, "FilterPass2", Store.Pw.FILTER)
        Store.setPassword(context, "SettingsPass3", Store.Pw.SETTINGS)
        assertFalse(Store.checkPassword(context, "MainPass1", Store.Pw.SETTINGS))
        assertFalse(Store.checkPassword(context, "FilterPass2", Store.Pw.SETTINGS))
        assertTrue(Store.checkPassword(context, "SettingsPass3", Store.Pw.SETTINGS))
        assertTrue(Store.checkPassword(context, "MainPass1", Store.Pw.MAIN))
        assertTrue(Store.checkPassword(context, "FilterPass2", Store.Pw.FILTER))
        repeat(5) { Store.checkPassword(context, "wrong", Store.Pw.SETTINGS) }
        assertTrue(Store.lockoutRemainingMs(context, Store.Pw.SETTINGS) > 0)
        assertEquals(0L, Store.lockoutRemainingMs(context, Store.Pw.MAIN))
        assertEquals(0L, Store.lockoutRemainingMs(context, Store.Pw.FILTER))
    }

    @Test
    fun settingsPersistAndCapAnExistingUnlockWithoutExtendingIt() {
        Store.setLastUnlockMinutes(context, 120)
        Store.setUnlockedUntil(context, System.currentTimeMillis() + 120 * 60_000L)
        val config = TimingConfig(mapOf(TimingOption.MAX_UNLOCK to 10,
            TimingOption.DEFAULT_UNLOCK to 5, TimingOption.SITE_WAIT to 2), listOf(1, 5, 10))
        Store.saveTimingConfig(context, config)
        assertEquals(10, Store.timingConfig(context)[TimingOption.MAX_UNLOCK])
        assertEquals(listOf(1, 5, 10), Store.timingConfig(context).unlockPresets)
        assertEquals(5, Store.lastUnlockMinutes(context))
        val capped = Store.unlockedUntil(context)
        assertTrue(capped - System.currentTimeMillis() <= 10 * 60_000L)
        Store.saveTimingConfig(context, TimingConfig())
        assertEquals(capped, Store.unlockedUntil(context))
    }

    @Test
    fun legacyWhitelistEntriesUseTheConfiguredWaitWithoutLosingSites() {
        val addedAt = System.currentTimeMillis() - 2 * 60_000L
        prefs.edit().putStringSet("sites", setOf("$addedAt|example.com")).commit()
        assertTrue(Store.activePatterns(context).isEmpty())
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.SITE_WAIT to 1)))
        assertEquals(listOf("example.com"), Store.activePatterns(context))
        assertEquals(addedAt, Store.sites(context).single().addedAt)
    }
}
