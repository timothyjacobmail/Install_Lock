package com.example.installlock

import android.app.SearchManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSearchRecoveryTest {
    @Test fun replacementQueryStartsFreshInsteadOfResumingBlockedResults() {
        val intent = GoogleSearchRecoveryActivity.searchIntent("weather tomorrow")
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals(BrowserPackages.GOOGLE_APP, intent.`package`)
        assertEquals("weather tomorrow", intent.getStringExtra(SearchManager.QUERY))
        assertTrue(intent.getBooleanExtra(SearchManager.EXTRA_NEW_SEARCH, false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test fun blockedResultOpensPrivateEditorWithoutLaunchingGoogleAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recovery = GoogleSearchRecoveryActivity.openIntent(context, "anime", "bleach")
        val component = recovery.component!!
        assertEquals(context.packageName, component.packageName)
        assertEquals(GoogleSearchRecoveryActivity::class.java.name, component.className)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
        assertFalse(recovery.hasExtra(SearchManager.QUERY))
        assertEquals("bleach", recovery.getStringExtra("original_query"))
        assertTrue(recovery.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun excludedTermIsPassedToGoogleAsAnOperator() {
        val query = GoogleSearchQuery.excluding("bleach", "anime")!!
        val intent = GoogleSearchRecoveryActivity.searchIntent(query)
        assertEquals("bleach -anime", intent.getStringExtra(SearchManager.QUERY))
        assertEquals(BrowserPackages.GOOGLE_APP, intent.`package`)
    }
}
