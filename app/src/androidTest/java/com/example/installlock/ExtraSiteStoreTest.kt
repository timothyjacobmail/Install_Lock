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
class ExtraSiteStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("extra_site_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private fun newContext() = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val context = newContext()

    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun additionsNormalizeWholeDomainsWithoutPasswords() {
        assertTrue(Store.extraLockedSites(context).isEmpty())
        assertTrue(Store.addExtraLockedSite(context, "https://www.Example.com/some/path?q=1"))
        assertEquals(listOf("example.com"), Store.extraLockedSites(context).map { it.domain })
        assertFalse(Store.addExtraLockedSite(context, "example.com"))
        assertFalse(Store.addExtraLockedSite(context, "*.com"))
        assertFalse(Store.addExtraLockedSite(context, "co.uk"))
        assertTrue(Store.Pw.entries.none { Store.hasPassword(context, it) })
    }

    @Test fun deadlinePersistsAndIsUnaffectedBySettingsOrWhitelistSwitch() {
        Store.addExtraLockedSite(context, "example.com")
        val before = System.currentTimeMillis()
        val deadline = Store.requestExtraSiteRemoval(context, "example.com")!!
        assertTrue(deadline >= before + 45 * 60_000L)
        assertTrue(deadline <= System.currentTimeMillis() + 45 * 60_000L)
        Store.saveTimingConfig(context, TimingConfig(mapOf(TimingOption.SITE_WAIT to 0,
            TimingOption.KEYWORD_REMOVAL_WAIT to 1)))
        Store.setChromeLock(context, false)
        assertEquals(deadline, Store.extraLockedSites(newContext()).single().removeAt)
        assertEquals(deadline, Store.requestExtraSiteRemoval(context, "example.com"))
    }

    @Test fun dueSitesAreRemovedPermanentlyAndReaddingCancelsPendingRemoval() {
        Store.addExtraLockedSite(context, "example.com")
        Store.requestExtraSiteRemoval(context, "example.com")
        assertTrue(Store.addExtraLockedSite(context, "example.com"))
        assertNull(Store.extraLockedSites(context).single().removeAt)
        prefs.edit().putStringSet("extraSiteRemovalRequests", setOf("1|example.com")).commit()
        assertTrue(Store.extraLockedSites(context).isEmpty())
        assertTrue(Store.extraLockedSites(newContext()).isEmpty())
        assertNull(Store.requestExtraSiteRemoval(context, "example.com"))
    }
}
