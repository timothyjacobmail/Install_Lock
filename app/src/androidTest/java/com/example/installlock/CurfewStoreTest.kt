package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurfewStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("curfew_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private fun time(text: String) = LocalDateTime.parse(text).atZone(CurfewStore.zone()).toInstant().toEpochMilli()
    private fun pkg(name: String, installed: Long, system: Boolean = false) = PackageInfo().apply {
        packageName = name; firstInstallTime = installed
        applicationInfo = ApplicationInfo().apply { flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
    }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun appealPersistsAcrossReadsAndRejectsLateAndRepeatedRequests() {
        assertTrue(CurfewStore.appeal(context, time("2026-10-20T20:59:59")))
        assertEquals("2026-10-20", CurfewStore.appealedNight(context))
        assertFalse(CurfewStore.appeal(context, time("2026-10-20T20:59:59")))
        assertFalse(CurfewStore.appeal(context, time("2026-10-21T00:30:00")))
        assertFalse(CurfewStore.appeal(context, time("2026-10-21T21:00:00")))
        assertTrue(CurfewStore.appeal(context, time("2026-10-21T08:00:00")))
    }

    @Test fun onlyPermanentAppsRemainAfterDownloadNightEnds() {
        val old = pkg("test.old", time("2026-10-19T10:00:00"))
        val fresh = pkg("test.fresh", time("2026-10-20T08:00:00"))
        val essential = pkg("test.system", fresh.firstInstallTime, true)
        val apps = listOf(old, fresh, essential,
            pkg(context.packageName, fresh.firstInstallTime), pkg("test.home", fresh.firstInstallTime))
        val enrolled = CurfewStore.enroll(context, apps, "test.home", time("2026-10-20T10:00:00"))
        assertEquals(CurfewRules.permanentPackages + "test.fresh", enrolled)
        // Updating an app does not reset its download day.
        fresh.lastUpdateTime = time("2026-10-21T01:00:00")
        assertEquals(enrolled, CurfewStore.enroll(context, apps, "test.home", time("2026-10-21T03:59:59")))
        assertEquals(CurfewRules.permanentPackages,
            CurfewStore.enroll(context, apps, "test.home", time("2026-10-21T04:00:00")))
        assertEquals(CurfewRules.permanentPackages, CurfewStore.targets(context))
        assertTrue("test.fresh" in CurfewStore.managed(context))
    }

    @Test fun legacyAccumulatedTargetsAreRemovedAndRetainedOnlyForReleaseRetries() {
        prefs.edit().putString("activationDay", "2026-10-01")
            .putStringSet("targets", setOf("test.old.system", "com.instagram.android", "test.old.download"))
            .putString("appealedNight", "2026-10-20").commit()
        val apps = listOf(pkg("test.old.system", time("2026-10-01T12:00:00"), true),
            pkg("test.old.download", time("2026-10-19T12:00:00")))
        assertEquals(CurfewRules.permanentPackages,
            CurfewStore.enroll(context, apps, null, time("2026-10-20T23:00:00")))
        assertFalse(prefs.contains("activationDay"))
        assertEquals("2026-10-20", CurfewStore.appealedNight(context))
        assertTrue("test.old.system" in CurfewStore.managed(context))
        // A subsequent sweep must still retry releasing removed system apps.
        CurfewStore.enroll(context, apps, null, time("2026-10-21T12:00:00"))
        assertEquals(CurfewRules.permanentPackages, CurfewStore.targets(context))
        assertTrue("test.old.system" in CurfewStore.managed(context))
    }
}
