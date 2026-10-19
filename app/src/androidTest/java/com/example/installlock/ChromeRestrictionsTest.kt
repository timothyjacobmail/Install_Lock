package com.example.installlock

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromeRestrictionsTest {
    @Test
    fun missingPolicyIsRestoredWithoutTogglingLock() {
        val restored = ChromeRestrictions.update(Bundle(), true, listOf("example.com"))!!
        assertEquals("*", JSONArray(restored.getString("URLBlocklist")).getString(0))
        assertEquals("example.com", JSONArray(restored.getString("URLAllowlist")).getString(0))
        assertNull(ChromeRestrictions.update(restored, true, listOf("example.com")))
        // An external reset must be repaired even if we previously sent the same list.
        assertNotNull(ChromeRestrictions.update(Bundle(), true, listOf("example.com")))
    }

    @Test
    fun pendingSitesBlockEverythingAndActivationUpdatesPolicy() {
        val pending = ChromeRestrictions.update(Bundle(), true, emptyList())!!
        assertEquals("[\"*\"]", pending.getString("URLBlocklist"))
        assertEquals(0, JSONArray(pending.getString("URLAllowlist")).length())
        val active = ChromeRestrictions.update(pending, true, listOf("example.com/path"))!!
        assertEquals("example.com/path", JSONArray(active.getString("URLAllowlist")).getString(0))
        val removed = ChromeRestrictions.update(active, true, emptyList())!!
        assertEquals("[]", removed.getString("URLAllowlist"))
    }

    @Test
    fun oldArrayPoliciesAreMigratedAndOtherRestrictionsArePreserved() {
        val old = Bundle().apply {
            putStringArray("URLBlocklist", arrayOf("*"))
            putStringArray("URLAllowlist", arrayOf("example.com"))
            putBoolean("ForceGoogleSafeSearch", true)
        }
        val migrated = ChromeRestrictions.update(old, true, listOf("example.com"))!!
        assertEquals("[\"example.com\"]", migrated.getString("URLAllowlist"))
        assertTrue(migrated.getBoolean("ForceGoogleSafeSearch"))
        val off = ChromeRestrictions.update(migrated, false, emptyList())!!
        assertFalse(off.containsKey("URLBlocklist"))
        assertFalse(off.containsKey("URLAllowlist"))
        assertTrue(off.getBoolean("ForceGoogleSafeSearch"))
        assertNull(ChromeRestrictions.update(off, false, emptyList()))
    }

    @Test
    fun siteOrderDoesNotCauseRepeatedPolicyPushes() {
        val current = ChromeRestrictions.update(Bundle(), true, listOf("b.example", "a.example"))!!
        assertNull(ChromeRestrictions.update(current, true, listOf("a.example", "b.example")))
    }
}
