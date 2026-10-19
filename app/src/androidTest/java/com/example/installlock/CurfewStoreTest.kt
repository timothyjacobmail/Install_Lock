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

    @Test fun enrollmentPersistsThroughMidnightRuleRemovalAndPackageUpdates() {
        val old = pkg("test.old", time("2026-10-19T10:00:00"))
        val fresh = pkg("test.fresh", time("2026-10-20T08:00:00"))
        val essential = pkg("test.system", fresh.firstInstallTime, true)
        val apps = listOf(old, fresh, essential)
        val defaults = setOf("test.browser", context.packageName, "test.home")
        val enrolled = CurfewStore.enroll(context, apps, defaults, "test.home", time("2026-10-20T10:00:00"))
        assertEquals(setOf("test.fresh", "test.browser"), enrolled)
        assertEquals(enrolled, CurfewStore.enroll(context, apps, emptySet(), "test.home", time("2026-10-21T10:00:00")))
        assertEquals(enrolled, CurfewStore.targets(context))
    }
}
