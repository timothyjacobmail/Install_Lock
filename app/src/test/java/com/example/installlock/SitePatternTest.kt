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
            "https://example.com:8443/A%2Fb/Page?tracking=private", "http://localhost:40715/callback?state=example-state&code=example-code")) {
            for (choice in SharedSite.whitelistOptions(text)) assertEquals(choice, SitePattern.normalize(choice))
        }
    }

    @Test fun acceptsLocalCallbacksAndDropsTemporarySignInParameters() {
        val callback = "http://LOCALHOST:40715/callback?state=example-state&iss=https://accounts.example&code=example-code&scope=email+profile#complete"
        assertEquals("localhost:40715/callback", SitePattern.normalizeForEntry(callback))
        assertEquals("localhost:40715/callback", SitePattern.normalize("localhost:40715/callback"))
        assertEquals("localhost/callback", SitePattern.normalize("localhost/callback"))
        assertEquals("localhost", SitePattern.normalizeForEntry("http://localhost/?code=example-code"))
        assertEquals("localhost:40715", SitePattern.normalizeForEntry("http://localhost:40715?state=example-state"))
        assertEquals("127.0.0.1:40715/callback", SitePattern.normalizeForEntry("http://127.0.0.1:40715/callback?code=example-code"))
    }

    @Test fun localSupportDoesNotAcceptArbitraryWordsOrInvalidPorts() {
        for (text in listOf("ordinarytext", "intranet/callback", "localhost:0/callback", "localhost:65536/callback",
            "localhost:bad/callback", "localhost:40715/callback|other.example", "localhost/*", "file://localhost/callback"))
            assertNull(text, SitePattern.normalize(text))
        // Intentional query-scoped website rules retain their existing semantics.
        assertEquals("youtube.com/watch?v=allowed", SitePattern.normalize("https://youtube.com/watch?v=allowed"))
        assertEquals("youtube.com/watch?v=allowed", SitePattern.normalizeForEntry("https://youtube.com/watch?v=allowed"))
    }
}
