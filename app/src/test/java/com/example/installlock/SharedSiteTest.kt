package com.example.installlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
