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
class BrowserKeywordStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("keyword_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @After
    fun cleanUp() { prefs.edit().clear().commit() }

    @Test
    fun wordsAreAddedImmediatelyWithoutAnyPasswords() {
        assertEquals(setOf("anime", "manga", "webtoon"), Store.browserKeywords(context))
        assertTrue(Store.addBrowserKeyword(context, "  Comic   Book "))
        assertTrue("comic book" in Store.browserKeywords(context))
        assertFalse(Store.addBrowserKeyword(context, "Comic book"))
        assertFalse(Store.addBrowserKeyword(context, "invalid|word"))
        assertTrue(Store.Pw.entries.none { Store.hasPassword(context, it) })
    }

    @Test
    fun requestedWordStaysBlockedUntilItsExactDeadlineThenStaysRemoved() {
        val before = System.currentTimeMillis()
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        assertTrue(deadline >= before + 15 * 60_000L)
        assertTrue(deadline <= System.currentTimeMillis() + 15 * 60_000L)
        Store.applyDueKeywordRemovals(context, deadline - 1)
        assertTrue("anime" in Store.browserKeywords(context))
        Store.applyDueKeywordRemovals(context, deadline)
        assertEquals(setOf("manga", "webtoon"), Store.browserKeywords(context))
        assertTrue(prefs.getStringSet("keywordRemovalRequests", emptySet())!!.isEmpty())
        // Changing the duration later must never resurrect a removed word.
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.KEYWORD_REMOVAL_WAIT to 60)))
        assertFalse("anime" in Store.browserKeywords(context))
    }

    @Test
    fun cancellingOrReaddingStopsRemovalImmediately() {
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        Store.cancelKeywordRemoval(context, "anime")
        Store.applyDueKeywordRemovals(context, deadline)
        assertTrue("anime" in Store.browserKeywords(context))
        val next = Store.requestKeywordRemoval(context, "anime")!!
        assertTrue(Store.addBrowserKeyword(context, "ANIME"))
        Store.applyDueKeywordRemovals(context, next)
        assertTrue("anime" in Store.browserKeywords(context))
        assertNull(Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
    }

    @Test
    fun repeatedRequestsAndSettingsChangesDoNotShortenExistingWaits() {
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        assertEquals(deadline, Store.requestKeywordRemoval(context, "anime"))
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.KEYWORD_REMOVAL_WAIT to 1)))
        assertEquals(deadline, Store.requestKeywordRemoval(context, "anime"))
        assertTrue(Store.requestKeywordRemoval(context, "manga")!! < deadline)
        assertNull(Store.requestKeywordRemoval(context, "not blocked"))
    }

    @Test
    fun filterCannotBeDisabledWithoutAnExistingTaskPassword() {
        assertTrue(Store.browserKeywordFilterEnabled(context))
        assertFalse(Store.disableBrowserKeywordFilter(context, "AnyPass1"))
        assertTrue(Store.browserKeywordFilterEnabled(context))
    }

    @Test
    fun onlyTaskPasswordCanTurnOffFilteringAndEnablingNeedsNoPassword() {
        Store.setPassword(context, "MainPass1", Store.Pw.MAIN)
        Store.setPassword(context, "SettingsPass2", Store.Pw.SETTINGS)
        Store.setPassword(context, "AppLockPass3", Store.Pw.APP_LOCK)
        installTaskTestCredentials(context, "FilterPass4", TaskKind.KEYWORD_OFF)
        for (password in listOf("MainPass1", "SettingsPass2", "AppLockPass3")) {
            assertFalse(Store.disableBrowserKeywordFilter(context, password))
            assertTrue(Store.browserKeywordFilterEnabled(context))
        }
        assertTrue(Store.disableBrowserKeywordFilter(context, "FilterPass4"))
        assertFalse(Store.browserKeywordFilterEnabled(context))
        Store.enableBrowserKeywordFilter(context)
        assertTrue(Store.browserKeywordFilterEnabled(context))
    }

    @Test
    fun passwordLockoutCannotBeBypassedWithTheCorrectPasswordButEnablingStillWorks() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.KEYWORD_OFF)
        assertTrue(Store.disableBrowserKeywordFilter(context, "FilterPass4"))
        repeat(5) { TaskPasswords.check(context, TaskScope(TaskKind.KEYWORD_OFF), "wrong") }
        Store.enableBrowserKeywordFilter(context)
        assertTrue(Store.browserKeywordFilterEnabled(context))
        assertFalse(Store.disableBrowserKeywordFilter(context, "FilterPass4"))
        assertTrue(Store.browserKeywordFilterEnabled(context))
    }

    @Test
    fun switchingFilterModesPreservesWordsPendingRemovalsAndGuardRecoveryState() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.KEYWORD_OFF)
        Store.addBrowserKeyword(context, "extra word")
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        val guarded = setOf("com.android.chrome", "example.browser")
        Store.setKeywordGuardPackages(context, guarded)
        assertTrue(Store.disableBrowserKeywordFilter(context, "FilterPass4"))
        Store.enableBrowserKeywordFilter(context)
        assertTrue("extra word" in Store.browserKeywords(context))
        assertEquals(deadline, Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
        assertEquals(guarded, Store.keywordGuardPackages(context))
        Store.setKeywordGuardPackages(context, emptySet())
        assertTrue(Store.keywordGuardPackages(context).isEmpty())
    }
}
