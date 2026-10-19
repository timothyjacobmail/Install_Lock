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
    private val prefs = base.getSharedPreferences("scoped_app_locks_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val access = "YoutubeAccess1"
    private val removal = "YoutubeRemove2"
    private fun setup() {
        val scopes = TaskScope.appTasks(youtube) + TaskScope.appTasks(chrome) + TaskScope(TaskKind.WHITELIST_OFF)
        assertNull(TaskPasswords.vault(context).install(scopes.map {
            NewTaskPassword(it, when (it) {
                TaskScope(TaskKind.APP_UNLOCK, youtube) -> access
                TaskScope(TaskKind.APP_REMOVE, youtube) -> removal
                else -> "${it.id}Pass3"
            }, if (it.kind == TaskKind.APP_UNLOCK) 15 else null)
        }, false))
    }
    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun legacyGlobalUnlockAndBroadPasswordsCannotOpenAnyApp() {
        prefs.edit().putLong("unlockedUntil", System.currentTimeMillis() + 600_000).commit()
        Store.setPassword(context, "OldBroadPass1", Store.Pw.APP_LOCK)
        assertEquals(0L, Store.unlockedUntil(context, youtube))
        assertFalse(Store.unlockApp(context, youtube, 1, "OldBroadPass1"))
        assertFalse(Store.removeAppLock(context, youtube, "OldBroadPass1"))
    }

    @Test fun youtubeAccessDoesNotUnlockChromeRemoveLocksOrDisableWebsiteProtection() {
        setup()
        assertTrue(Store.unlockApp(context, youtube, 10, access))
        assertFalse(Store.unlockApp(context, chrome, 10, access))
        assertEquals(0L, Store.unlockedUntil(context, chrome))
        assertFalse(Store.removeAppLock(context, youtube, access))
        assertFalse(TaskPasswords.check(context, TaskScope(TaskKind.WHITELIST_OFF), access))
        assertTrue(Store.removeAppLock(context, youtube, removal))
        assertTrue(chrome in Store.protectedApps(context))
        assertEquals(0L, Store.unlockedUntil(context, youtube))
    }

    @Test fun addingALockOnlyRelocksThatAppAndKeepsConfiguredCredentials() {
        setup()
        assertTrue(Store.unlockApp(context, youtube, 10, access))
        val until = Store.unlockedUntil(context, youtube)
        Store.addAppLock(context, "example.newapp")
        assertEquals(until, Store.unlockedUntil(context, youtube))
        Store.addAppLock(context, youtube)
        assertEquals(0L, Store.unlockedUntil(context, youtube))
        assertTrue(Store.unlockApp(context, youtube, 5, access))
        Store.lockProtectedApps(context)
        assertEquals(0L, Store.unlockedUntil(context, youtube))
    }

    @Test fun globalMaximumCannotBypassAppSpecificMaximum() {
        setup()
        assertFalse(Store.unlockApp(context, youtube, 16, access))
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.MAX_UNLOCK to 10,
            TimingOption.DEFAULT_UNLOCK to 5), listOf(1, 5, 10)))
        assertFalse(Store.unlockApp(context, youtube, 11, access))
        assertTrue(Store.unlockApp(context, youtube, 10, access))
        assertFalse(Store.unlockApp(context, "example.notprotected", 5, access))
    }
}
