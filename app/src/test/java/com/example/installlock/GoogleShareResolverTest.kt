package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class GoogleShareResolverTest {
    @Test fun resolvesTheActualHubermanRedirectChainIncludingGooglesIntermediateShareEndpoint() {
        val fetched = mutableListOf<String>()
        val resolver = GoogleShareResolver { uri ->
            fetched.add(uri.toString())
            if (uri.host == "share.google") GoogleShareResolver.Response(302,
                "https://www.google.com/share.google?q=6Qt7NHRlh888L6bgK")
            else GoogleShareResolver.Response(301, "https://www.hubermanlab.com/")
        }
        val resolved = resolver.resolve("https://share.google/6Qt7NHRlh888L6bgK")!!
        assertEquals("https://www.hubermanlab.com/", resolved)
        assertEquals(listOf("hubermanlab.com"), SharedSite.whitelistOptions(resolved))
        assertEquals(listOf("https://share.google/6Qt7NHRlh888L6bgK",
            "https://www.google.com/share.google?q=6Qt7NHRlh888L6bgK"), fetched)
    }

    @Test fun redirectsToTheOriginalPageWithoutFetchingTheDestination() {
        val fetched = mutableListOf<String>()
        val resolver = GoogleShareResolver { uri ->
            fetched.add(uri.toString())
            GoogleShareResolver.Response(302, "https://www.hubermanlab.com/Topics/Sleep/Post?tracking=x")
        }
        val resolved = resolver.resolve("https://share.google/6Qt7NHRlh888L6bgK")!!
        assertEquals(listOf("https://share.google/6Qt7NHRlh888L6bgK"), fetched)
        assertEquals(listOf("hubermanlab.com/Topics/Sleep/Post", "hubermanlab.com/Topics/Sleep/",
            "hubermanlab.com/Topics/", "hubermanlab.com"), SharedSite.whitelistOptions(resolved))
    }

    @Test fun followsGoogleRedirectWrappersAndDecodesTheDestinationOnce() {
        val resolver = GoogleShareResolver {
            GoogleShareResolver.Response(301, "https://www.google.com/url?url=https%3A%2F%2Fexample.com%2FA%252Fb%2FPost%3Fid%3D123")
        }
        assertEquals("https://example.com/A%2Fb/Post?id=123", resolver.resolve("https://share.google/test"))
    }

    @Test fun directGoogleUrlQueryNeedsNoNetworkRequest() {
        val resolver = GoogleShareResolver { error("No request expected") }
        assertEquals("https://example.com/Page", resolver.resolve("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2FPage"))
    }

    @Test fun supportsSearchAppAndRelativeGoogleRedirects() {
        val fetched = mutableListOf<String>()
        val resolver = GoogleShareResolver { uri ->
            fetched.add(uri.toString())
            if (uri.path == "/first") GoogleShareResolver.Response(307, "/second")
            else GoogleShareResolver.Response(308, "https://example.com/page")
        }
        assertEquals("https://example.com/page", resolver.resolve("https://search.app/first"))
        assertEquals(listOf("https://search.app/first", "https://search.app/second"), fetched)
    }

    @Test fun redirectLoopsDoNotBecomeWhitelistChoices() {
        val resolver = GoogleShareResolver { GoogleShareResolver.Response(302, "/loop") }
        assertNull(resolver.resolve("https://share.google/loop"))
    }

    @Test fun networkErrorsAndMissingLocationsStayUnresolved() {
        assertNull(GoogleShareResolver { throw java.io.IOException("Offline") }.resolve("https://share.google/test"))
        assertNull(GoogleShareResolver { GoogleShareResolver.Response(302) }.resolve("https://share.google/test"))
        assertNull(GoogleShareResolver { GoogleShareResolver.Response(404) }.resolve("https://share.google/test"))
    }

    @Test fun rejectsNonWebTargetsCredentialsAndUnsafeWrapperRequests() {
        for (target in listOf("intent://open#Intent;end", "javascript:alert(1)", "file:///etc/hosts",
            "https://user:secret@example.com/Page", "http://share.google/next", "https://share.google:8443/next")) {
            assertNull(target, GoogleShareResolver { GoogleShareResolver.Response(302, target) }.resolve("https://share.google/test"))
        }
    }

    @Test fun readsCanonicalMetadataWithoutTakingUnrelatedPageLinks() {
        val html = """<a href="https://wrong.example/privacy">Privacy</a>
            <link href="https://example.com/Original/Post?a=1&amp;b=2" rel="canonical">"""
        assertEquals("https://example.com/Original/Post?a=1&b=2",
            GoogleShareResolver { GoogleShareResolver.Response(200, html = html) }.resolve("https://share.google/test"))
        assertNull(GoogleShareResolver { GoogleShareResolver.Response(200,
            html = """<a href="https://wrong.example/privacy">Privacy</a>""") }.resolve("https://share.google/test"))
    }

    @Test fun readsOpenGraphMetadataAndHtmlEscapedCharacters() {
        val html = """<meta content='https&#x3a;//example.com/Original/Post?a=1&amp;b=2' property='og:url'>"""
        assertEquals("https://example.com/Original/Post?a=1&b=2",
            GoogleShareResolver { GoogleShareResolver.Response(200, html = html) }.resolve("https://share.google/test"))
    }

    @Test fun supportsMetaRefreshToAnotherGoogleWrapper() {
        val resolver = GoogleShareResolver { uri ->
            if (uri.path == "/first") GoogleShareResolver.Response(200,
                html = """<meta http-equiv="refresh" content="0;url=/second">""")
            else GoogleShareResolver.Response(302, "https://example.com/final")
        }
        assertEquals("https://example.com/final", resolver.resolve("https://share.google/first"))
    }

    @Test fun wrapperCanonicalUrlsDoNotMasqueradeAsTheOriginalWebsite() {
        val html = """<link rel="canonical" href="https://share.google/test">"""
        assertNull(GoogleShareResolver { GoogleShareResolver.Response(200, html = html) }.resolve("https://share.google/test"))
    }

    @Test fun redirectDepthIsBounded() {
        var count = 0
        val resolver = GoogleShareResolver { count++; GoogleShareResolver.Response(302, "/next$count") }
        assertNull(resolver.resolve("https://share.google/start"))
        assertEquals(8, count)
    }
}
