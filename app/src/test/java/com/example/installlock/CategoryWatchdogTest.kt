package com.example.installlock

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CategoryWatchdogTest {
    private val base = 1_800_000_000_000L
    private fun clock(t: Long) = WatchdogClock(base + t, t, 7)
    private fun watch(until: Long, initial: WatchdogState = WatchdogState(), from: Long = 0): WatchdogState {
        var s = CategoryWatchdog.observe(initial, true, clock(from))
        var t = from + 250
        while (t <= until) { s = CategoryWatchdog.observe(s, true, clock(t)); t += 250 }
        return s
    }

    @Test fun firstWarningIsImmediateFinalAt5AndCooldownAt10() {
        assertEquals(1, CategoryWatchdog.warning(watch(0)))
        assertEquals(1, CategoryWatchdog.warning(watch(4750)))
        assertEquals(2, CategoryWatchdog.warning(watch(5000)))
        assertEquals(2, CategoryWatchdog.warning(watch(9750)))
        val locked = watch(10000)
        assertEquals(0, CategoryWatchdog.warning(locked))
        assertEquals(30 * 60_000L, CategoryWatchdog.remaining(locked, clock(10000)))
    }

    @Test fun leavingPausesAndReturningResumesAcrossSitesAndBrowsers() {
        val threeSeconds = watch(3000)
        val left = CategoryWatchdog.observe(threeSeconds, false, clock(3250))
        assertEquals(0, CategoryWatchdog.warning(left))
        val back = CategoryWatchdog.observe(left, true, clock(6000))
        assertEquals(3000L, back.spent)
        assertEquals(2, CategoryWatchdog.warning(watch(8000, back, 6000)))
        assertEquals(CategoryWatchdog.COOLDOWN_MS,
            CategoryWatchdog.remaining(watch(13000, back, 6000), clock(13000)))
    }

    @Test fun tenMinuteAbsenceResetsExposureButBriefSwitchesDoNot() {
        val left = CategoryWatchdog.observe(watch(5000), false, clock(5250))
        assertEquals(5000L, CategoryWatchdog.observe(left, true, clock(604999)).spent)
        assertEquals(0L, CategoryWatchdog.observe(left, true, clock(605000)).spent)
    }

    @Test fun refreshEventsAndRepeatedObservationsAtTheSameTimeDoNotAddTime() {
        val s = watch(3000)
        var next = s
        repeat(50) { next = CategoryWatchdog.observe(next, true, clock(3000)) }
        assertEquals(s.spent, next.spent)
    }

    @Test fun staleOrMissingForegroundSamplesCannotAccumulateUnobservedTime() {
        val s = watch(3000)
        assertEquals(3000L, CategoryWatchdog.observe(s, true, clock(6000)).spent)
        val unknown = CategoryWatchdog.observe(s, false, clock(3250))
        assertEquals(3000L, CategoryWatchdog.observe(unknown, true, clock(6000)).spent)
    }

    @Test fun idleSamplingDoesNotConstantlyRewritePreferences() {
        val s = WatchdogState()
        assertEquals(s, CategoryWatchdog.observe(s, false, clock(10000)))
        val paused = CategoryWatchdog.observe(watch(3000), false, clock(3250))
        assertEquals(paused, CategoryWatchdog.observe(paused, false, clock(4000)))
    }

    @Test fun cooldownAttemptsDoNotRestartOrExtendIt() {
        val locked = watch(10000)
        val retry = CategoryWatchdog.observe(locked, true, clock(70000))
        assertEquals(locked, retry)
        assertEquals(29 * 60_000L, CategoryWatchdog.remaining(retry, clock(70000)))
    }

    @Test fun sameBootCooldownUsesMonotonicTimeNotWallClock() {
        val locked = watch(10000)
        assertEquals(CategoryWatchdog.COOLDOWN_MS - 1000,
            CategoryWatchdog.remaining(locked, WatchdogClock(base + 99_000_000, 11000, 7)))
    }

    @Test fun rebootRebasesRemainingCooldownAndThenUsesMonotonicTime() {
        val locked = watch(10000)
        val reboot = WatchdogClock(base + 70000, 100, 8)
        val restored = CategoryWatchdog.observe(locked, false, reboot)
        assertEquals(29 * 60_000L, CategoryWatchdog.remaining(restored, reboot))
        assertEquals(28 * 60_000L, CategoryWatchdog.remaining(restored, reboot.copy(elapsed = 60100)))
    }

    @Test fun expiredCooldownStartsANewWarningCycle() {
        val locked = watch(10000)
        val expired = clock(10000 + CategoryWatchdog.COOLDOWN_MS)
        assertEquals(0L, CategoryWatchdog.remaining(locked, expired))
        val next = CategoryWatchdog.observe(locked, true, expired)
        assertEquals(0L, next.spent)
        assertEquals(1, CategoryWatchdog.warning(next))
    }

    @Test fun rebootDuringExposureRetainsBudgetWithoutCountingDowntime() {
        val s = watch(5000)
        val resumed = CategoryWatchdog.observe(s, true, WatchdogClock(base + 15000, 100, 8))
        assertEquals(5000L, resumed.spent)
        assertEquals(2, CategoryWatchdog.warning(resumed))
    }

    @Test fun hostnameMatchingIncludesDescendantsButNotLookalikes() {
        val categories = WebsiteCategories(setOf("mangadex.org", "webtoons.com"))
        assertEquals("mangadex.org", categories.mangaDomain("API.MANGADEX.ORG."))
        assertEquals("webtoons.com", categories.mangaDomain("www.webtoons.com"))
        assertNull(categories.mangaDomain("mangadex.org.evil.example"))
        assertNull(categories.mangaDomain("notmangadex.org"))
        assertNull(categories.mangaDomain("google.com"))
        assertNull(categories.mangaDomain(null))
    }

    @Test fun shippedCatalogIsAvailableOfflineWithAttribution() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val data = WebsiteCategories.parse(File(root, "categories/manga-domains.txt").readText())
        assertTrue(data.size >= 500)
        assertTrue(File(root, "categories/README.txt").readText().contains("CC BY-SA 4.0"))
        assertTrue(File(root, "licenses/CC-BY-SA-4.0.txt").length() > 10000)
        val categories = WebsiteCategories(data + WebsiteCategories.additions)
        assertNotNull(categories.mangaDomain("mangadex.org"))
        assertNotNull(categories.mangaDomain("www.webtoons.com"))
    }

    @Test fun categoryCooldownCannotBeClearedByAnAppUnlockOrInstallCategoryExemption() {
        val reasons = AppLockReasons(categoryCooldownUntil = base + 1000)
        assertTrue(reasons.shouldLock)
        assertTrue(AppLockStatus("test", "Test", reasons, true, "Shopping").explanation().contains("category cooldown"))
        assertTrue(reasons.copy(curfewUntil = base + 2000, categoryCooldownUntil = null).shouldLock)
        assertFalse(reasons.copy(categoryCooldownUntil = null).shouldLock)
        assertTrue(AppLockReasons(categoryServiceMissing = true).shouldLock)
    }
}
