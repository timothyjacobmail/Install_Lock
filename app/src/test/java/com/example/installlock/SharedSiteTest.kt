package com.example.installlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SharedSiteTest {
    @Test
    fun sharesOnlyTheDomainRatherThanTheWholePage() {
        assertEquals("example.com", SharedSite.domainFromText("https://www.Example.com/path?token=secret#section"))
        assertEquals("news.example.com", SharedSite.domainFromText("https://news.example.com:8443/story"))
    }

    @Test
    fun supportsChromeTextWithATitleAndBareAddresses() {
        assertEquals("example.com", SharedSite.domainFromText("An interesting page\nhttps://example.com/read"))
        assertEquals("example.com", SharedSite.domainFromText("www.example.com/path"))
        assertEquals("example.com", SharedSite.domainFromText("HTTP://EXAMPLE.COM"))
    }

    @Test
    fun rejectsNonWebsiteAndMalformedShares() {
        for (text in listOf(null, "", "ordinary text", "javascript:alert(1)", "file:///etc/hosts",
            "chrome://policy", "https://*.example.com", "https://", "https://localhost")) {
            assertNull(text, SharedSite.domainFromText(text))
        }
    }

    @Test
    fun usesTheActualHostWhenTheUrlContainsCredentials() {
        assertEquals("actual.example", SharedSite.domainFromText("https://trusted.example@actual.example/path"))
    }

    @Test
    fun offersTheRequestedRedditPathHierarchyFromNarrowestToBroadest() {
        assertEquals(listOf("reddit.com/r/sharks/posts", "reddit.com/r/sharks/", "reddit.com/r/", "reddit.com"),
            SharedSite.whitelistOptions("reddit.com/r/sharks/posts"))
    }

    @Test
    fun extractsChoicesFromChromeSharesAndOmitsTokensAndFragments() {
        assertEquals(listOf("reddit.com/r/sharks/posts", "reddit.com/r/sharks/", "reddit.com/r/", "reddit.com"),
            SharedSite.whitelistOptions("An interesting page\nhttps://www.Reddit.com/r/sharks/posts?token=secret#section"))
    }

    @Test
    fun preservesCaseAndEncodedSegmentsWithoutTreatingEncodedSlashesAsDirectories() {
        assertEquals(listOf("news.example.com/Topics/Sharks%2FWhales/Post", "news.example.com/Topics/Sharks%2FWhales/",
            "news.example.com/Topics/", "news.example.com"),
            SharedSite.whitelistOptions("https://news.EXAMPLE.com/Topics/Sharks%2FWhales/Post"))
    }

    @Test
    fun directorySharesPreserveTheirTrailingSlashWithoutDuplicatingChoices() {
        assertEquals(listOf("reddit.com/r/sharks/", "reddit.com/r/", "reddit.com"),
            SharedSite.whitelistOptions("https://reddit.com/r/sharks/"))
    }

    @Test
    fun rootPagesAndDomainsOfferOnlyOneChoice() {
        for (text in listOf("example.com", "https://www.example.com/", "https://example.com/?q=private#fragment")) {
            assertEquals(listOf("example.com"), SharedSite.whitelistOptions(text))
        }
        assertEquals(listOf("example.com/story", "example.com"), SharedSite.whitelistOptions("https://example.com/story"))
    }

    @Test
    fun explicitPortsAndActualHostArePreservedButCredentialsAreNotCopied() {
        assertEquals(listOf("actual.example:8443/Read/Post", "actual.example:8443/Read/", "actual.example:8443"),
            SharedSite.whitelistOptions("https://trusted.example:secret@actual.example:8443/Read/Post"))
    }

    @Test
    fun malformedOrOverbroadSharedAddressesHaveNoSuggestedChoices() {
        for (text in listOf(null, "", "ordinary text", "file:///etc/hosts", "https://*.example.com/a",
            "https://localhost/a", "https://co.uk/path", "https://example.com:0/path", "https://example.com:65536/path")) {
            assertEquals(text, emptyList<String>(), SharedSite.whitelistOptions(text))
        }
    }

    @Test
    fun preservesRepeatedPathSeparatorsAndNeverSuggestsALiteralWildcard() {
        assertEquals(listOf("example.com/r//Page", "example.com/r//", "example.com/r/", "example.com"),
            SharedSite.whitelistOptions("https://example.com/r//Page"))
        assertEquals(listOf("example.com/r/%2A/posts", "example.com/r/%2A/", "example.com/r/", "example.com"),
            SharedSite.whitelistOptions("https://example.com/r/*/posts"))
    }

    @Test
    fun usesHubermanDomainDisplayedBesideTheGoogleShortLink() {
        val text = "www.hubermanlab.com https://share.google/6Qt7NHRlh888L6bgK"
        assertEquals(listOf("hubermanlab.com"), SharedSite.whitelistOptions(text))
        assertEquals("https://share.google/6Qt7NHRlh888L6bgK", SharedSite.shortLinkFromText(text))
        assertEquals(listOf("hubermanlab.com"), SharedSite.whitelistOptions(text.replace(" ", "\n")))
    }

    @Test
    fun neverSuggestsGoogleShortenerPathsOrTheWholeShortenerDomain() {
        for (text in listOf("https://share.google/abc", "https://search.app/xyz", "share.google/abc")) {
            assertEquals(emptyList<String>(), SharedSite.whitelistOptions(text))
            assertNotNull(SharedSite.shortLinkFromText(text))
        }
    }

    @Test
    fun prefersAnExplicitOriginalUrlOverTheShortenedUrlInEitherOrder() {
        for (text in listOf("https://share.google/abc\nhttps://example.com/Topics/Post",
            "https://example.com/Topics/Post\nhttps://share.google/abc")) {
            assertEquals(listOf("example.com/Topics/Post", "example.com/Topics/", "example.com"), SharedSite.whitelistOptions(text))
            assertNull(SharedSite.shortLinkFromText(text))
        }
    }

    @Test
    fun supportsDisplayedDomainsWithSearchAppLinksButDoesNotGuessFromTitlesOrVersionNumbers() {
        assertEquals(listOf("example.com"), SharedSite.whitelistOptions("Interesting page\nwww.example.com\nhttps://search.app/xyz"))
        assertEquals(emptyList<String>(), SharedSite.whitelistOptions("Sleep tips 16.114.3\nhttps://share.google/abc"))
        assertEquals(listOf("hubermanlab.com"), SharedSite.whitelistOptions("www\\.hubermanlab.com https://share.google/abc"))
    }
}
