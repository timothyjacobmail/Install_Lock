package com.example.installlock

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedSiteRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recoveryIsPrivateAndNeverLaunchesTheBlockedUrlInChrome() {
        val recovery = BlockedSiteActivity.openIntent(context, "https://example.com/blocked")
        val component = recovery.component!!
        assertEquals(BlockedSiteActivity::class.java.name, component.className)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
        assertNull(recovery.data)
        assertTrue(recovery.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun whitelistRequestUsesExistingShareFormWithAllParentPathChoices() {
        val share = BlockedSiteActivity.whitelistIntent(context, "https://reddit.com/r/sharks/posts?tracking=123#comments")
        assertEquals(MainActivity::class.java.name, share.component!!.className)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/plain", share.type)
        assertEquals(listOf("reddit.com/r/sharks/posts", "reddit.com/r/sharks/", "reddit.com/r/", "reddit.com"),
            SharedSite.whitelistOptions(share.getStringExtra(Intent.EXTRA_TEXT)))
    }

    @Test fun temporaryCallbackCredentialsAreAbsentFromBothIntents() {
        val url = "http://localhost:40715/callback?state=temporary&code=synthetic-secret&iss=https://accounts.google.com#fragment"
        val recovery = BlockedSiteActivity.openIntent(context, url)
        assertEquals("localhost:40715/callback", recovery.getStringExtra("blocked_site"))
        val share = BlockedSiteActivity.whitelistIntent(context, recovery.getStringExtra("blocked_site")!!)
        assertEquals("localhost:40715/callback", share.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun fullRedditPathSurvivesRecoveryAndTheWhitelistForm() {
        val recovery = BlockedSiteActivity.openIntent(context,
            "https://www.reddit.com/r/mechanicalEngineering/comments/abc/title/?utm_source=google#comments")
        val share = BlockedSiteActivity.whitelistIntent(context, recovery.getStringExtra("blocked_site")!!)
        val choices = SharedSite.whitelistOptions(share.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("reddit.com/r/mechanicalEngineering/comments/abc/title/", choices.first())
        assertTrue(choices.contains("reddit.com/r/mechanicalEngineering/"))
        assertEquals("reddit.com", choices.last())
    }

    @Test fun googleShortLinkStillReachesTheExistingResolver() {
        val share = BlockedSiteActivity.whitelistIntent(context, "https://share.google/exampleToken?tracking=123")
        assertEquals("https://share.google/exampleToken", SharedSite.shortLinkFromText(share.getStringExtra(Intent.EXTRA_TEXT)))
    }
}
