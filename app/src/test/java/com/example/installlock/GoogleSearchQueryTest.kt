package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class GoogleSearchQueryTest {
    private val blocked = setOf("anime", "manga", "comic book")

    @Test fun addsMinusToTheOriginalSearch() {
        assertEquals("bleach -anime", GoogleSearchQuery.excluding("bleach", "anime"))
        assertEquals("bleach -anime", GoogleSearchQuery.excluding("  bleach  ", "ANIME"))
    }

    @Test fun quotesBlockedPhrasesAndPunctuation() {
        assertEquals("stories -\"comic book\"", GoogleSearchQuery.excluding("stories", "comic book"))
        assertEquals("medicine -\"x-ray\"", GoogleSearchQuery.excluding("medicine", "x-ray"))
    }

    @Test fun preservesOriginalOperatorsAndOtherExclusions() {
        assertEquals("\"bleach cleaner\" site:example.com -manga -anime",
            GoogleSearchQuery.excluding("\"bleach cleaner\" site:example.com -manga", "anime"))
    }

    @Test fun doesNotDuplicateAnExistingExclusion() {
        assertEquals("bleach -ANIME", GoogleSearchQuery.excluding("bleach -ANIME", "anime"))
        assertEquals("bleach -\"anime\"", GoogleSearchQuery.excluding("bleach -\"anime\"", "anime"))
        assertEquals("stories -\"Comic   Book\"", GoogleSearchQuery.excluding("stories -\"Comic   Book\"", "comic book"))
    }

    @Test fun consecutiveBlocksCanExcludeAnotherTrigger() {
        val first = GoogleSearchQuery.excluding("bleach", "anime")!!
        assertEquals("bleach -anime -manga", GoogleSearchQuery.excluding(first, "manga"))
    }

    @Test fun requiresAQueryAndAValidBlockedTerm() {
        assertNull(GoogleSearchQuery.excluding(" ", "anime"))
        assertNull(GoogleSearchQuery.excluding("bleach", "a\"b"))
        assertNull(GoogleSearchQuery.excluding("bleach", ""))
    }

    @Test fun exclusionsDoNotTriggerQueryValidation() {
        assertNull(GoogleSearchQuery.blockedMatch("bleach -anime", blocked))
        assertNull(GoogleSearchQuery.blockedMatch("stories -\"comic book\" -anime -manga", blocked))
    }

    @Test fun positiveBlockedTermsStillPreventSearching() {
        assertEquals("anime", GoogleSearchQuery.blockedMatch("anime bleach -anime", blocked))
        assertEquals("manga", GoogleSearchQuery.blockedMatch("bleach -anime manga", blocked))
        assertEquals("comic book", GoogleSearchQuery.blockedMatch("comic book -anime", blocked))
    }

    @Test fun malformedMinusOperatorsCannotHideBlockedTerms() {
        for (query in listOf("bleach - anime", "bleach --anime", "bleach -\"anime",
            "bleach -\"anime\"junk", "bleach anime-related", "bleach -anime,other")) {
            assertEquals(query, "anime", GoogleSearchQuery.blockedMatch(query, blocked))
        }
    }

    @Test fun minusInsidePositiveQuotesIsNotAnExclusion() {
        assertEquals("anime", GoogleSearchQuery.blockedMatch("\"bleach -anime\"", blocked))
        assertEquals("manga", GoogleSearchQuery.blockedMatch("bleach -anime \"manga\"", blocked))
    }

    @Test fun excludingAWordFromTheQueryDoesNotExemptItInResults() {
        val query = GoogleSearchQuery.excluding("bleach", "anime")!!
        assertNull(GoogleSearchQuery.blockedMatch(query, blocked))
        assertEquals("anime", KeywordMatcher(blocked).findMatch("Bleach anime result still appears"))
        assertEquals("anime", KeywordMatcher(blocked).findMatch("A result discussing -anime syntax"))
    }

    @Test fun recognizesNativeQueryControlsWithoutTreatingEveryTextViewAsASearch() {
        assertTrue(GoogleQueryField.matches("android.widget.EditText", null, false))
        assertTrue(GoogleQueryField.matches("android.view.View", null, true))
        assertTrue(GoogleQueryField.matches("android.widget.TextView",
            "com.google.android.googlequicksearchbox:id/search_box_text", false))
        assertFalse(GoogleQueryField.matches("android.widget.TextView", "search_box", false))
        assertFalse(GoogleQueryField.matches("android.widget.TextView", "other.app:id/search_box", false))
        assertFalse(GoogleQueryField.matches("android.widget.TextView",
            "com.google.android.googlequicksearchbox:id/result_title", false))
    }
}
