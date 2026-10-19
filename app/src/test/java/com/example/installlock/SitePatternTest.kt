package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class SitePatternTest {
    @Test fun normalizesTheHostWhilePreservingCaseSensitivePaths() {
        assertEquals("example.com/Read/Post", SitePattern.normalize(" HTTPS://WWW.Example.COM/Read/Post "))
        assertEquals("example.com/A%2Fb/", SitePattern.normalize("example.com/A%2Fb/"))
    }

    @Test fun preservesDirectoryBoundariesButCollapsesTheDomainRoot() {
        assertEquals("reddit.com/r/sharks/", SitePattern.normalize("reddit.com/r/sharks/"))
        assertEquals("reddit.com/r/", SitePattern.normalize("reddit.com/r/"))
        assertEquals("reddit.com", SitePattern.normalize("reddit.com/"))
    }

    @Test fun rejectsWildcardsStorageDelimitersAndPublicSuffixes() {
        for (text in listOf("*", "*.example.com", "co.uk", "com.au", "example.com/path|other.com", "example.com/*")) {
            assertNull(text, SitePattern.normalize(text))
        }
        assertEquals("example.co.uk/r/", SitePattern.normalize("example.co.uk/r/"))
    }

    @Test fun validatesExplicitPorts() {
        assertEquals("example.com:8443/Read/", SitePattern.normalize("EXAMPLE.COM:8443/Read/"))
        for (text in listOf("example.com:0/path", "example.com:65536/path", "example.com:9999999999/path")) {
            assertNull(SitePattern.normalize(text))
        }
    }

    @Test fun allSuggestedChoicesCanBeSavedWithoutChangingTheirScope() {
        for (text in listOf("https://reddit.com/r/sharks/posts", "https://www.example.com/Read/Post/",
            "https://example.com:8443/A%2Fb/Page?tracking=private")) {
            for (choice in SharedSite.whitelistOptions(text)) assertEquals(choice, SitePattern.normalize(choice))
        }
    }
}
