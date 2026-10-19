package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class AppLockStatusTest {
    private fun status(reasons: AppLockReasons, suspended: Boolean?) =
        AppLockStatus("com.android.chrome", "Chrome", reasons, suspended)

    @Test fun disabledAccessibilityAndAppLockAreBothExplained() {
        val reasons = AppLockReasons(keywordServiceMissing = true, appLock = true)
        val text = status(reasons, true).explanation()
        assertTrue(text.startsWith("Chrome is locked because:"))
        assertTrue(text.contains("accessibility setting is disabled"))
        assertTrue(text.contains("app-lock password"))
        assertEquals(2, reasons.descriptions().size)
    }

    @Test fun enabledButDisconnectedServiceDoesNotSayDisabled() {
        val text = status(AppLockReasons(keywordServiceMissing = true, accessibilityEnabled = true), true).explanation()
        assertTrue(text.contains("has not connected"))
        assertFalse(text.contains("setting is disabled"))
    }

    @Test fun rejectedSuspensionDoesNotClaimTheAppIsLocked() {
        val pending = status(AppLockReasons(appLock = true), false)
        assertTrue(pending.needsAttention)
        assertTrue(pending.explanation().contains("lock pending"))
        assertFalse(pending.explanation().contains("Chrome is locked"))
    }

    @Test fun failedReleaseIsExplainedWithoutInventingARule() {
        val stuck = status(AppLockReasons(), true)
        assertTrue(stuck.needsAttention)
        assertTrue(stuck.explanation().contains("still locked"))
        assertTrue(stuck.explanation().contains("no current Install Lock rule"))
        assertTrue(stuck.reasons.descriptions().isEmpty())
    }

    @Test fun unknownStateDoesNotClaimSuccessfulLockOrUnlock() {
        val unknown = status(AppLockReasons(appLock = true), null)
        assertTrue(unknown.needsAttention)
        assertTrue(unknown.explanation().contains("could not be checked"))
        assertTrue(unknown.explanation().contains("Current lock reasons"))
        assertFalse(unknown.explanation().contains("Chrome is locked"))
    }

    @Test fun removedReasonsDisappearAndNoLockIsReported() {
        val open = status(AppLockReasons(accessibilityEnabled = true), false)
        assertFalse(open.needsAttention)
        assertEquals("Chrome has no active app lock.", open.explanation())
    }

    @Test fun installBrowserCurboxAndNeverInstallExplanationsIncludeRecovery() {
        val reasons = AppLockReasons(installWaitUntil = 1_800_000_000_000L, otherBrowser = true,
            curboxMissing = true, neverInstallRule = "webtoon")
        val text = status(reasons, true).explanation()
        assertEquals(4, reasons.descriptions().size)
        assertTrue(text.contains("waiting period ends at"))
        assertTrue(text.contains("blocks other browsers"))
        assertTrue(text.contains("grace period has ended"))
        assertTrue(text.contains("\"webtoon\""))
        assertTrue(text.contains("filter password"))
    }

    @Test fun googleNeedsItsVpnEvenWithoutAnAppLockOrKeywordRestriction() {
        val reasons = AppLockReasons(googleVpnMissing = true)
        val google = AppLockStatus("com.google.android.googlequicksearchbox", "Google", reasons, true)
        assertTrue(reasons.shouldLock)
        assertTrue(google.needsAttention)
        assertTrue(google.explanation().startsWith("Google is locked because:"))
        assertTrue(google.explanation().contains("built-in VPN is not running"))
        assertTrue(google.explanation().contains("Google VPN filter"))
    }

    @Test fun clearingOneGoogleRequirementDoesNotClearOtherLocks() {
        val allMissing = AppLockReasons(googleVpnMissing = true, keywordServiceMissing = true, appLock = true)
        assertEquals(3, allMissing.descriptions().size)
        val unlockedApps = allMissing.copy(appLock = false)
        assertTrue(unlockedApps.shouldLock)
        val accessibilityRestored = unlockedApps.copy(keywordServiceMissing = false)
        assertTrue(accessibilityRestored.shouldLock)
        assertEquals(1, accessibilityRestored.descriptions().size)
        val vpnRestoredFirst = unlockedApps.copy(googleVpnMissing = false)
        assertTrue(vpnRestoredFirst.shouldLock)
        assertEquals(1, vpnRestoredFirst.descriptions().size)
        assertFalse(accessibilityRestored.copy(googleVpnMissing = false).shouldLock)
    }
}
