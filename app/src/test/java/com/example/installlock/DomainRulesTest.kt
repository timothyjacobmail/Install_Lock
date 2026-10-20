package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class DomainRulesTest {
    private val blockManga = DomainRule("manga", DomainAction.BLOCK, DomainMatchType.CONTAINS, "manga")
    private val allowOrg = DomainRule("org", DomainAction.ALLOW, DomainMatchType.SUFFIX, "org")

    @Test fun firstBlockWinsOverLaterAllow() {
        val match = DomainRules(listOf(blockManga, allowOrg)).match("manga.org")!!
        assertEquals(DomainAction.BLOCK, match.rule.action)
        assertEquals(1, match.position)
    }

    @Test fun movingAllowAboveBlockReversesTheResult() {
        val match = DomainRules(listOf(allowOrg, blockManga)).match("manga.org")!!
        assertEquals(DomainAction.ALLOW, match.rule.action)
        assertEquals(1, match.position)
    }

    @Test fun laterMatchesReportTheirActualPosition() {
        assertEquals(2, DomainRules(listOf(blockManga, allowOrg)).match("wikipedia.org")!!.position)
        assertEquals(1, DomainRules(listOf(blockManga, allowOrg)).match("readmanga.com")!!.position)
    }

    @Test fun unmatchedDomainsPassWithoutInventingAnAllowRule() {
        assertNull(DomainRules(listOf(blockManga, allowOrg)).match("example.com"))
        assertNull(DomainRules(emptyList()).match("manga.org"))
    }

    @Test fun suffixRequiresALabelBoundary() {
        val engine = DomainRules(listOf(allowOrg))
        assertNotNull(engine.match("org"))
        assertNotNull(engine.match("a.b.org"))
        assertNull(engine.match("evilorg"))
        assertNull(engine.match("org.evil.com"))
    }

    @Test fun domainSuffixIncludesRootAndSubdomains() {
        val engine = DomainRules(listOf(allowOrg.copy(value = "example.org")))
        assertNotNull(engine.match("example.org"))
        assertNotNull(engine.match("www.example.org"))
        assertNull(engine.match("fakeexample.org"))
    }

    @Test fun exactMatchExcludesSubdomainsAndUsesNormalizedHost() {
        val rule = allowOrg.copy(type = DomainMatchType.EXACT, value = "manga.org")
        val engine = DomainRules(listOf(rule))
        assertNotNull(engine.match("MANGA.ORG."))
        assertNull(engine.match("www.manga.org"))
    }

    @Test fun hostnamesSupportInternationalDomains() {
        assertEquals("xn--bcher-kva.org", DomainRules.normalizeHost("Bücher.ORG"))
        assertNotNull(DomainRules(listOf(allowOrg.copy(type = DomainMatchType.EXACT,
            value = "xn--bcher-kva.org"))).match("bücher.org"))
    }

    @Test fun ruleInputsAreValidatedAndNormalized() {
        assertEquals("org", DomainRules.normalizeValue(DomainMatchType.SUFFIX, " .ORG "))
        assertEquals("org", DomainRules.normalizeValue(DomainMatchType.SUFFIX, "*.org"))
        assertEquals("manga", DomainRules.normalizeValue(DomainMatchType.CONTAINS, " ＭＡＮＧＡ "))
        for (input in listOf("", "example..org", "-bad.org", "https://example.org", "example.org/path", "a|b.org")) {
            assertNull(input, DomainRules.normalizeValue(DomainMatchType.EXACT, input))
        }
        assertNull(DomainRules.normalizeValue(DomainMatchType.CONTAINS, "manga anime"))
    }

    @Test fun addressParsingUsesHostnameOnly() {
        val host = DomainRules.hostFromAddress("https://example.org/manga?q=manga#anime")!!
        assertEquals("example.org", host)
        assertEquals(DomainAction.ALLOW, DomainRules(listOf(blockManga, allowOrg)).match(host)!!.rule.action)
        assertEquals("manga.org", DomainRules.hostFromAddress("MANGA.org:8443/path"))
        assertEquals("evil.com", DomainRules.hostFromAddress("https://manga.org@evil.com/path"))
        assertEquals("intranet", DomainRules.hostFromAddress("https://intranet/path"))
        assertEquals("example.org", DomainRules.hostFromAddress("example.org/redirect?next=https://manga.org/"))
        assertEquals("localhost", DomainRules.hostFromAddress("localhost:40715/callback?iss=https://accounts.example"))
        assertEquals("localhost", DomainRules.hostFromAddress("http://localhost:40715/callback"))
    }

    @Test fun addressParsingRejectsSearchTextAndBrowserInternalPages() {
        for (input in listOf("", "manga", "manga anime", "chrome://settings", "about:blank", "javascript:alert(1)", "https:///x",
            "https://example.org:bad", "example.org:0", "example.org:65536", "example.org:+443")) {
            assertNull(input, DomainRules.hostFromAddress(input))
        }
    }

    @Test fun encodingRoundTripsWithoutLosingOrderOrActions() {
        val rules = listOf(blockManga, allowOrg)
        assertEquals(rules, rules.joinToString("\n") { it.encode() }.lines().mapNotNull { DomainRule.decode(it) })
        assertNull(DomainRule.decode("x|INVALID|SUFFIX|org"))
        assertNull(DomainRule.decode("x|BLOCK|INVALID|org"))
        assertNull(DomainRule.decode("x|BLOCK|SUFFIX|org|extra"))
        assertNull(DomainRule.decode("|BLOCK|SUFFIX|org"))
    }

    @Test fun nativeAddressIdsAreScopedToTheActiveBrowser() {
        assertTrue(BrowserAddress.isAddressField("com.android.chrome", "com.android.chrome:id/url_bar"))
        assertTrue(BrowserAddress.isAddressField("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"))
        assertFalse(BrowserAddress.isAddressField("com.android.chrome", "page:id/url_bar"))
        assertFalse(BrowserAddress.isAddressField("com.android.chrome", "com.android.chrome:id/result_text"))
        assertFalse(BrowserAddress.isAddressField("com.android.chrome", null))
    }
}
