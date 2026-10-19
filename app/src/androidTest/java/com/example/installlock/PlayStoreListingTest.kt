package com.example.installlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayStoreListingTest {
    private fun listing(category: String, packageName: String = "example.app") = """
        <script type="application/ld+json">{
          "@type":"SoftwareApplication",
          "url":"https://play.google.com/store/apps/details/App_Name?id\u003d$packageName\u0026hl\u003den",
          "applicationCategory":"$category"
        }</script>
    """.trimIndent()

    @Test fun readsTheCurrentPlayStructuredMetadataAndEscapedUrl() {
        for (category in listOf("MUSIC_AND_AUDIO", "FINANCE", "SHOPPING", "ENTERTAINMENT")) {
            assertEquals(category, PlayStoreListing.category(listing(category), "example.app"))
        }
    }

    @Test fun recommendationsAndWrongPackageListingsCannotExemptAnApp() {
        assertNull(PlayStoreListing.category(listing("FINANCE", "different.app"), "example.app"))
        assertEquals("ENTERTAINMENT", PlayStoreListing.category(
            listing("FINANCE", "different.app") + listing("ENTERTAINMENT"), "example.app"))
        assertNull(PlayStoreListing.category("<a href='/store/apps/category/FINANCE'>Finance</a>", "example.app"))
    }

    @Test fun invalidOrMissingStructuredMetadataStaysUnverified() {
        assertNull(PlayStoreListing.category("<script type='application/ld+json'>{bad json}</script>", "example.app"))
        assertNull(PlayStoreListing.category(listing(""), "example.app"))
        assertNull(PlayStoreListing.category(listing("Music & Audio"), "example.app"))
        assertNull(PlayStoreListing.category(listing("SHOPPING").replace("SoftwareApplication", "Person"), "example.app"))
    }

    @Test fun requiresTheExactPlayListingHostAndPackage() {
        val valid = listing("SHOPPING")
        assertNull(PlayStoreListing.category(valid.replace("play.google.com", "play.google.com.fake.com"), "example.app"))
        assertNull(PlayStoreListing.category(valid.replace("https://", "http://"), "example.app"))
        assertNull(PlayStoreListing.category(valid.replace("/store/apps/details/", "/different/"), "example.app"))
        assertNull(PlayStoreListing.category(valid.replace("\\u0026hl", "\\u0026id=other.app\\u0026hl"), "example.app"))
    }

    @Test fun neverConfusesDeveloperMetadataWithTheAppsOwnCategory() {
        val html = listing("ENTERTAINMENT").replace("\"applicationCategory\":\"ENTERTAINMENT\"",
            """"author":{"@type":"Person","applicationCategory":"FINANCE"},"applicationCategory":"ENTERTAINMENT"""")
        assertEquals("ENTERTAINMENT", PlayStoreListing.category(html, "example.app"))
    }
}
