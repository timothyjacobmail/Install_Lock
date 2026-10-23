package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class ChromeTabRecoveryTest {
    @Test fun onlyAnOriginOnlyReadOnlyChromeUrlNeedsHandoff() {
        val id = "com.android.chrome:id/url_bar"
        assertTrue(ChromeTabRecovery.needsFullTab(id, false, false, "www.reddit.com"))
        assertFalse(ChromeTabRecovery.needsFullTab(id, true, false, "www.reddit.com"))
        assertFalse(ChromeTabRecovery.needsFullTab(id, false, true, "www.reddit.com"))
        assertFalse(ChromeTabRecovery.needsFullTab(id, false, false, "reddit.com/r/mechanicalEngineering/"))
        assertFalse(ChromeTabRecovery.needsFullTab("page:id/url_bar", false, false, "reddit.com"))
        assertFalse(ChromeTabRecovery.needsFullTab(id, false, false, "Search or type URL"))
    }

    @Test fun handoffCannotChooseAnotherBrowserOrWebPageControl() {
        val chrome = "com.android.chrome"
        assertTrue(ChromeTabRecovery.isOpenInChrome(chrome, "$chrome:id/menu_item_text", "Open in Chrome"))
        assertTrue(ChromeTabRecovery.isOpenInChrome(chrome, "$chrome:id/open_in_browser_id", "Open in Chrome"))
        for (title in listOf("Open in Firefox", "Open in browser", "Open in app", "Open in Incognito Chrome"))
            assertFalse(ChromeTabRecovery.isOpenInChrome(chrome, "$chrome:id/menu_item_text", title))
        assertFalse(ChromeTabRecovery.isOpenInChrome("page", "$chrome:id/menu_item_text", "Open in Chrome"))
        assertFalse(ChromeTabRecovery.isOpenInChrome(chrome, "page:id/link", "Open in Chrome"))
    }

    @Test fun installedChrome155BrowserLabelReachesTheFullTabAction() {
        val chrome = "com.android.chrome"
        for (id in listOf("$chrome:id/menu_item_text", "$chrome:id/open_in_browser_id")) {
            assertTrue(ChromeTabRecovery.isOpenInChrome(chrome, id, "Open in Chrome browser"))
            assertTrue(ChromeTabRecovery.isOpenInChrome(chrome, id, " Open in Chrome browser "))
            assertFalse(ChromeTabRecovery.isOpenInChrome(chrome, id, "Open in Firefox browser"))
            assertFalse(ChromeTabRecovery.isOpenInChrome(chrome, id, "Open in default browser"))
        }
    }

    @Test fun fullRedditAddressOffersSubredditAndKeepsOtherSubredditsBlocked() {
        val url = "https://www.reddit.com/r/mechanicalEngineering/comments/abc/title/?utm_source=google#comments"
        val choices = SharedSite.whitelistOptions(url)
        val subreddit = "reddit.com/r/mechanicalEngineering/"
        assertTrue(choices.contains(subreddit))
        assertEquals("reddit.com", choices.last())
        assertTrue(choices.none { '?' in it || '#' in it })
        val filter = ChromeNavigationFilter(ChromeNavigationConfig(true, listOf(subreddit), emptySet()))
        assertNull(filter.block(url))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("https://www.reddit.com/r/other/"))
        assertEquals(ChromeNavigationBlock.WHITELIST, filter.block("https://www.reddit.com/"))
    }
}
