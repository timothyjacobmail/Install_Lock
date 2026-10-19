package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class RapidScrollTest {
    @Test fun websitePathsIdentifyFeedsButNotArticlesSearchOrComments() {
        for (url in listOf("https://m.youtube.com/shorts/abc?feature=share", "youtube.com/shorts/abc",
            "https://www.instagram.com/reel/abc/", "instagram.com/reels/", "www.instagram.com/",
            "reddit.com/", "old.reddit.com/r/sharks/", "reddit.com/r/sharks/top/?t=week")) {
            assertNotNull(url, ShortFormFeeds.website(url))
        }
        for (url in listOf("youtube.com/watch?v=abc", "youtube.com/results?search_query=shorts",
            "reddit.com/r/sharks/comments/abc/title", "reddit.com/search?q=shorts", "reddit.com/message/inbox",
            "instagram.com/direct/inbox/", "instagram.com/p/abc/", "example.org/article/shorts",
            "youtube.com", "not a URL")) {
            assertNull(url, ShortFormFeeds.website(url))
        }
    }

    @Test fun lookalikeHostsAndUrlsInQueryStringsCannotClassifyAFeed() {
        for (url in listOf("https://youtube.com.evil.test/shorts/abc", "https://notyoutube.com/shorts/abc",
            "https://example.com/?url=https://reddit.com/", "https://instagram.com@evil.test/reels/"))
            assertNull(url, ShortFormFeeds.website(url))
    }

    @Test fun nativeShortsNeedsSelectedNavigationOrAPlayerNotJustATabId() {
        val youtube = "com.google.android.youtube"
        assertNull(ShortFormFeeds.nativeFeed(youtube, setOf("shorts_tab", "pivot_bar"), setOf("home")))
        assertEquals("YouTube Shorts", ShortFormFeeds.nativeFeed(youtube, emptySet(), setOf("shorts")))
        assertEquals("YouTube Shorts", ShortFormFeeds.nativeFeed(youtube, setOf("reel_recycler"), emptySet()))
        assertNull(ShortFormFeeds.nativeFeed("some.other.app", setOf("reel_recycler"), setOf("shorts")))
    }

    @Test fun nativeFeedsExcludeCommentAndMessageViewsButNotTheirActionButtons() {
        assertNotNull(ShortFormFeeds.nativeFeed("com.instagram.android", setOf("clips_viewer_container", "comment_button"), emptySet()))
        assertNull(ShortFormFeeds.nativeFeed("com.instagram.android", setOf("clips_viewer_container", "comments_list"), emptySet()))
        assertNotNull(ShortFormFeeds.nativeFeed("com.reddit.frontpage", setOf("feed_scroll", "post_title"), setOf("home")))
        assertNull(ShortFormFeeds.nativeFeed("com.reddit.frontpage", setOf("feed_scroll", "message_list"), setOf("home")))
        assertNull(ShortFormFeeds.nativeFeed("com.reddit.frontpage", setOf("feed_scroll"), setOf("search")))
        assertNotNull(ShortFormFeeds.nativeFeed("com.instagram.android", setOf("row_feed_container"), setOf("home")))
    }

    @Test fun sixDistinctBurstsAreRequiredAndAnimationsDoNotMultiplyThem() {
        val detector = RapidScrollBursts()
        repeat(5) { i ->
            detector.scroll(i * 1000L, true)
            detector.scroll(i * 1000L + 100, true)
            detector.scroll(i * 1000L + 200, true)
            assertFalse(detector.active(i * 1000L + 200))
        }
        detector.scroll(5000, true)
        assertTrue(detector.active(5000))
    }

    @Test fun aLongContinuousFlingNeverLooksLikeSixSeparateBursts() {
        val detector = RapidScrollBursts()
        for (t in 0L..20_000L step 100) detector.scroll(t, true)
        assertFalse(detector.active(20_000))
    }

    @Test fun oneFlingCannotKeepAnAlreadyQualifiedWarningActiveIndefinitely() {
        val detector = RapidScrollBursts()
        repeat(6) { detector.scroll(it * 1000L, true) }
        assertTrue(detector.active(5000))
        for (t in 5100L..10_000L step 100) detector.scroll(t, true)
        assertFalse(detector.active(8000))
    }

    @Test fun slowScrollingAndNonMovementCannotQualify() {
        val detector = RapidScrollBursts()
        repeat(20) { detector.scroll(it * 3100L, true) }
        assertFalse(detector.active(19 * 3100L))
        repeat(20) { detector.scroll(60_000 + it * 1000L, false) }
        assertFalse(detector.active(79_000))
    }

    @Test fun idlePausesAndExpiredHistoryDoesNotQualifyAgainWithOneSwipe() {
        val detector = RapidScrollBursts()
        repeat(6) { detector.scroll(it * 1000L, true) }
        assertTrue(detector.active(7999))
        assertFalse(detector.active(8000))
        detector.scroll(30_000, true)
        assertFalse(detector.active(30_000))
    }

    @Test fun resetAndMonotonicClockRollbackDiscardBurstHistory() {
        val detector = RapidScrollBursts()
        repeat(6) { detector.scroll(it * 1000L, true) }
        detector.reset()
        assertFalse(detector.active(5000))
        repeat(6) { detector.scroll(10_000 + it * 1000L, true) }
        assertTrue(detector.active(15_000))
        detector.scroll(10, true)
        assertFalse(detector.active(10))
    }

    @Test fun movementUsesVerticalDeltasAndOffsetFallbacks() {
        val movement = ScrollMovement()
        assertFalse(movement.vertical(1, 0, 0, 0, -1, -1))
        assertTrue(movement.vertical(1, 0, 200, 0, 0, 0))
        assertTrue(movement.vertical(1, 0, 100, 0, 0, -100))
        assertFalse(movement.vertical(1, 0, 100, 0, 0, 0))
        assertFalse(movement.vertical(1, 300, 110, 0, 300, 10))
        assertTrue(movement.vertical(2, -1, -1, -1, 0, 300))
    }

    @Test fun visibleItemRangeIsNotMovementAndSourceChangesDoNotInventIt() {
        val movement = ScrollMovement()
        assertFalse(movement.vertical(1, -1, -1, 10, -1, -1))
        assertFalse(movement.vertical(1, -1, -1, 10, -1, -1))
        assertTrue(movement.vertical(1, -1, -1, 11, -1, -1))
        assertFalse(movement.vertical(2, -1, -1, 100, -1, -1))
        assertFalse(movement.vertical(2, -1, -1, 101, -1, -1, horizontalOnly = true))
        movement.reset()
        assertFalse(movement.vertical(2, -1, -1, 102, -1, -1))
    }

    @Test fun sustainedRapidPatternWarnsAtFiveAndLocksAtTenAfterDetection() {
        val detector = RapidScrollBursts()
        var state = WatchdogState()
        for (t in 0L..15_000L step 250) {
            if (t % 1000 == 0L) detector.scroll(t, true)
            state = CategoryWatchdog.observe(state, detector.active(t), WatchdogClock(1_800_000_000_000L + t, t, 1))
            when (t) {
                4750L -> assertEquals(0, CategoryWatchdog.warning(state))
                5000L -> assertEquals(1, CategoryWatchdog.warning(state))
                10_000L -> assertEquals(2, CategoryWatchdog.warning(state))
                15_000L -> assertTrue(state.untilWall > 0)
            }
        }
    }

    @Test fun rapidCooldownExplainsTheRightCauseAndStillOverridesAppUnlocks() {
        val reasons = AppLockReasons(categoryCooldownUntil = 1_800_000_000_000L, rapidScrollCooldown = true)
        assertTrue(reasons.shouldLock)
        assertTrue(reasons.descriptions().single().startsWith("Rapid-scrolling cooldown"))
        assertTrue(reasons.descriptions().single().contains("Instagram, Reddit"))
        assertFalse(reasons.copy(categoryCooldownUntil = null).shouldLock)
    }
}
