package com.example.installlock

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CurfewRulesTest {
    private val zone = ZoneId.of("Asia/Dubai")
    private fun time(value: String, z: ZoneId = zone) = LocalDateTime.parse(value).atZone(z).toInstant().toEpochMilli()
    private val date = "2026-10-20"

    @Test fun normalCurfewStartsAt11AndEndsAt4Precisely() {
        assertNull(CurfewRules.blockedUntil(time("2026-10-20T22:59:59"), zone, null))
        val end = time("2026-10-21T04:00:00")
        assertEquals(end, CurfewRules.blockedUntil(time("2026-10-20T23:00:00"), zone, null))
        assertEquals(end, CurfewRules.blockedUntil(time("2026-10-21T00:00:00"), zone, null))
        assertEquals(end, CurfewRules.blockedUntil(end - 1, zone, null))
        assertNull(CurfewRules.blockedUntil(end, zone, null))
    }

    @Test fun appealMovesOnlyThisNightToMidnightAndKeeps4amEnd() {
        assertNull(CurfewRules.blockedUntil(time("2026-10-20T23:59:59"), zone, date))
        assertEquals(time("2026-10-21T04:00:00"),
            CurfewRules.blockedUntil(time("2026-10-21T00:00:00"), zone, date))
        assertNotNull(CurfewRules.blockedUntil(time("2026-10-21T23:00:00"), zone, date))
    }

    @Test fun appealDeadlineCannotBeBypassedByLeavingTheScreenOpen() {
        assertTrue(CurfewRules.canAppeal(time("2026-10-20T20:59:59"), zone, null))
        assertFalse(CurfewRules.canAppeal(time("2026-10-20T21:00:00"), zone, null))
        assertFalse(CurfewRules.canAppeal(time("2026-10-20T23:00:00"), zone, null))
        assertFalse(CurfewRules.canAppeal(time("2026-10-21T00:00:00"), zone, null))
        assertFalse(CurfewRules.canAppeal(time("2026-10-21T03:59:59"), zone, null))
    }

    @Test fun noStackingAndNextNightCanBeAppealedAfter4am() {
        assertFalse(CurfewRules.canAppeal(time("2026-10-20T12:00:00"), zone, date))
        assertTrue(CurfewRules.canAppeal(time("2026-10-21T04:00:00"), zone, date))
    }

    @Test fun nextBoundaryAccountsForAppealMidnightAndMorning() {
        assertEquals(time("2026-10-20T23:00:00"), CurfewRules.nextBoundary(time("2026-10-20T12:00:00"), zone, null))
        assertEquals(time("2026-10-21T00:00:00"), CurfewRules.nextBoundary(time("2026-10-20T23:00:00"), zone, date))
        assertEquals(time("2026-10-21T04:00:00"), CurfewRules.nextBoundary(time("2026-10-21T00:00:00"), zone, date))
        assertEquals(time("2026-10-21T23:00:00"), CurfewRules.nextBoundary(time("2026-10-21T04:00:00"), zone, date))
    }

    @Test fun oldAppealsDoNotAffectAnotherMonthOrYear() {
        assertNotNull(CurfewRules.blockedUntil(time("2027-10-20T23:00:00"), zone, date))
        assertEquals(LocalDate.parse("2026-12-31"), CurfewRules.night(time("2027-01-01T03:00:00"), zone))
    }

    @Test fun daylightSavingUsesCalendarNightRatherThanFiveHourDuration() {
        val newYork = ZoneId.of("America/New_York")
        val end = time("2026-11-01T04:00:00", newYork)
        assertEquals(end, CurfewRules.blockedUntil(time("2026-10-31T23:00:00", newYork), newYork, null))
        assertEquals(6 * 60 * 60 * 1000L, end - time("2026-10-31T23:00:00", newYork))
        assertNull(CurfewRules.blockedUntil(end, newYork, null))
    }

    @Test fun downloadsBelongOnlyToTheirCurfewNightAndExpireAt4am() {
        val now = time("2026-10-20T22:00:00")
        val installed = time("2026-10-20T12:00:00")
        assertTrue(CurfewRules.enrollInstall(installed, false, now, zone))
        assertFalse(CurfewRules.enrollInstall(time("2026-10-19T23:59:59"), false, now, zone))
        assertFalse(CurfewRules.enrollInstall(installed, true, now, zone))
        assertFalse(CurfewRules.enrollInstall(now + 1, false, now, zone))
        assertFalse(CurfewRules.enrollInstall(0, false, now, zone))
        assertTrue(CurfewRules.enrollInstall(installed, false, time("2026-10-21T03:59:59"), zone))
        assertFalse(CurfewRules.enrollInstall(installed, false, time("2026-10-21T04:00:00"), zone))
        assertFalse(CurfewRules.enrollInstall(installed, false, time("2026-10-21T23:00:00"), zone))
    }

    @Test fun downloadsDuringAfterMidnightCurfewExpireThatMorning() {
        val installed = time("2026-10-21T01:00:00")
        assertTrue(CurfewRules.enrollInstall(installed, false, time("2026-10-21T02:00:00"), zone))
        assertFalse(CurfewRules.enrollInstall(installed, false, time("2026-10-21T04:00:00"), zone))
        assertFalse(CurfewRules.enrollInstall(installed, false, time("2026-10-21T23:00:00"), zone))
        val nextNightDownload = time("2026-10-21T04:00:00")
        assertTrue(CurfewRules.enrollInstall(nextNightDownload, false, time("2026-10-21T23:00:00"), zone))
    }

    @Test fun curfewOverridesOtherwiseUnlockedAppsAndCategoryExemptions() {
        val reasons = AppLockReasons(curfewUntil = time("2026-10-21T04:00:00"))
        assertTrue(reasons.shouldLock)
        val status = AppLockStatus("test.app", "Test", reasons, true, "Music & Audio")
        assertTrue(status.explanation().contains("Night curfew"))
        assertTrue(status.explanation().contains("overrides temporary app unlocks"))
        assertFalse(reasons.copy(curfewUntil = null).shouldLock)
    }

    @Test fun appealDoesNotRemoveIndependentRestrictions() {
        val before = AppLockReasons(curfewUntil = time("2026-10-21T04:00:00"), googleVpnMissing = true, appLock = true)
        assertTrue(before.copy(curfewUntil = null).shouldLock)
        assertTrue(before.copy(curfewUntil = null, appLock = false).shouldLock)
    }
}
