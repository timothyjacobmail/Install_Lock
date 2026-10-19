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
class AppLockStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("app_locks_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @After
    fun cleanUp() { prefs.edit().clear().commit() }

    @Test
    fun addingDoesNotRequireAPasswordAndEndsAnExistingUnlock() {
        val original = Store.protectedApps(context)
        prefs.edit().putLong("unlockedUntil", System.currentTimeMillis() + 60_000).commit()
        Store.addAppLock(context, "example.newapp")
        assertFalse(Store.hasPassword(context, Store.Pw.APP_LOCK))
        assertEquals(original + "example.newapp", Store.protectedApps(context))
        assertEquals(0L, Store.unlockedUntil(context))
        assertFalse(Store.removeAppLock(context, "example.newapp", "AnyPass1"))
        assertFalse(Store.unlockProtectedApps(context, 1, "AnyPass1"))
        assertTrue("example.newapp" in Store.protectedApps(context))
    }

    @Test
    fun onlyTheAppLockPasswordCanRemoveLocksOrOpenAnUnlockWindow() {
        Store.setPassword(context, "MainPass1", Store.Pw.MAIN)
        Store.setPassword(context, "FilterPass2", Store.Pw.FILTER)
        Store.setPassword(context, "SettingsPass3", Store.Pw.SETTINGS)
        Store.setPassword(context, "AppLockPass4", Store.Pw.APP_LOCK)
        Store.addAppLock(context, "example.blocked")
        for (password in listOf("MainPass1", "FilterPass2", "SettingsPass3")) {
            assertFalse(Store.removeAppLock(context, "example.blocked", password))
            assertTrue("example.blocked" in Store.protectedApps(context))
        }
        assertEquals(0L, Store.unlockedUntil(context))
        assertTrue(Store.removeAppLock(context, "example.blocked", "AppLockPass4"))
        assertFalse("example.blocked" in Store.protectedApps(context))
        assertFalse(Store.unlockProtectedApps(context, 5, "MainPass1"))
        assertTrue(Store.unlockProtectedApps(context, 5, "AppLockPass4"))
        assertTrue(Store.unlockedUntil(context) > System.currentTimeMillis())
        assertEquals(5, Store.lastUnlockMinutes(context))
    }

    @Test
    fun removalKeepsAllOtherSelectedAppsAndExpiredOrWrongPasswordsCannotBypassLockout() {
        Store.setPassword(context, "AppLockPass4", Store.Pw.APP_LOCK)
        Store.addAppLock(context, "example.first")
        Store.addAppLock(context, "example.second")
        val original = Store.protectedApps(context)
        repeat(5) { assertFalse(Store.removeAppLock(context, "example.first", "wrong")) }
        assertTrue(Store.lockoutRemainingMs(context, Store.Pw.APP_LOCK) > 0)
        assertFalse(Store.removeAppLock(context, "example.first", "AppLockPass4"))
        assertFalse(Store.unlockProtectedApps(context, 5, "AppLockPass4"))
        assertEquals(original, Store.protectedApps(context))
        // Locking more apps remains available even during password lockout.
        Store.addAppLock(context, "example.third")
        assertEquals(original + "example.third", Store.protectedApps(context))
        prefs.edit().putLong("a_lockoutUntil", 0L).commit()
        assertTrue(Store.removeAppLock(context, "example.first", "AppLockPass4"))
        assertEquals(original - "example.first" + "example.third", Store.protectedApps(context))
    }

    @Test
    fun temporaryUnlockRespectsConfiguredMaximum() {
        Store.setPassword(context, "AppLockPass4", Store.Pw.APP_LOCK)
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.MAX_UNLOCK to 10,
            TimingOption.DEFAULT_UNLOCK to 5), listOf(1, 5, 10)))
        assertFalse(Store.unlockProtectedApps(context, 11, "AppLockPass4"))
        assertEquals(0L, Store.unlockedUntil(context))
        assertTrue(Store.unlockProtectedApps(context, 10, "AppLockPass4"))
        Store.lockProtectedApps(context)
        assertEquals(0L, Store.unlockedUntil(context))
    }
}
