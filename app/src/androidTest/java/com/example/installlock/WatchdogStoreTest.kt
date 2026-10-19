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
class WatchdogStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("watchdog_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun addedDomainsPersistAndCannotInjectPathsOrUnrelatedSuffixes() {
        assertTrue(WatchdogStore.addDomain(context, "https://new-manga.example/read/chapter"))
        assertTrue("new-manga.example" in WatchdogStore.domains(context))
        val lookup = WebsiteCategories(WatchdogStore.domains(context))
        assertEquals("new-manga.example", lookup.mangaDomain("www.new-manga.example"))
        assertNull(lookup.mangaDomain("new-manga.example.evil.test"))
        assertFalse(WatchdogStore.addDomain(context, "not a domain"))
    }

    @Test fun readingStatusDoesNotDiscardPersistedExposure() {
        WatchdogStore.observe(context, true)
        val saved = prefs.getString("state", null)
        assertNotNull(saved)
        assertEquals(0L, WatchdogStore.remaining(context))
        assertEquals(saved, prefs.getString("state", null))
    }

    @Test fun persistedCooldownSurvivesFreshStoreReadsAndPauses() {
        val c = WatchdogStore.clock(context)
        val value = listOf(0, 0, 0, -1, 0, -1, 0,
            c.wall + CategoryWatchdog.COOLDOWN_MS, c.elapsed + CategoryWatchdog.COOLDOWN_MS, c.boot).joinToString("|")
        prefs.edit().putString("state", value).commit()
        assertTrue(WatchdogStore.remaining(context) > 29 * 60_000)
        WatchdogStore.observe(context, false)
        assertTrue(WatchdogStore.remaining(context) > 29 * 60_000)
    }

    @Test fun rapidAndCategoryExposureAreIndependentAndStatusReadsPreserveBoth() {
        WatchdogStore.observe(context, true)
        WatchdogStore.observeRapid(context, true)
        val category = prefs.getString("state", null)
        val rapid = prefs.getString("rapidState", null)
        assertNotNull(category)
        assertNotNull(rapid)
        assertEquals(0L, WatchdogStore.remaining(context))
        assertEquals(category, prefs.getString("state", null))
        assertEquals(rapid, prefs.getString("rapidState", null))
        WatchdogStore.observeRapid(context, false)
        assertEquals(category, prefs.getString("state", null))
    }

    @Test fun rapidCooldownSurvivesReadsAndCannotBeExtendedByEitherDetector() {
        val c = WatchdogStore.clock(context)
        val value = listOf(0, 0, 0, -1, 0, -1, 0,
            c.wall + CategoryWatchdog.COOLDOWN_MS, c.elapsed + CategoryWatchdog.COOLDOWN_MS, c.boot).joinToString("|")
        prefs.edit().putString("rapidState", value).commit()
        assertTrue(WatchdogStore.rapidCooldown(context))
        WatchdogStore.observe(context, true)
        WatchdogStore.observeRapid(context, true)
        assertEquals(value, prefs.getString("rapidState", null))
        assertNull(prefs.getString("state", null))
        assertTrue(WatchdogStore.remaining(context) > 29 * 60_000)
    }
}
