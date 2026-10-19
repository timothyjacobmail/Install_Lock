package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class KeywordPageFilterTest {
    private val filter = KeywordPageFilter(setOf("movie", "anime"), mapOf("movie" to setOf("christian")))

    @Test fun aBlockedWordWithoutItsExceptionStillBlocks() {
        assertEquals("movie", filter.findBlock("A new movie is available"))
        assertNull(filter.findBlock("An ordinary page about gardening"))
    }

    @Test fun theExampleAllowsMovieAndChristianOnTheSamePageInEitherOrder() {
        assertNull(filter.findBlock("A CHRISTIAN family movie"))
        assertNull(filter.findBlock("Movie reviews and Christian recommendations"))
    }

    @Test fun exceptionsDoNotCarryOverToTheNextPage() {
        assertNull(filter.findBlock("Christian movie reviews"))
        assertEquals("movie", filter.findBlock("Movie reviews"))
    }

    @Test fun anExceptionForOneWordDoesNotAllowAnotherBlockedWord() {
        assertEquals("anime", filter.findBlock("Christian movie and anime reviews"))
        val ordered = KeywordPageFilter(setOf("movie", "zombie"), mapOf("movie" to setOf("christian")))
        assertEquals("zombie", ordered.findBlock("Christian movie and zombie reviews"))
    }

    @Test fun everyBlockedWordPresentNeedsItsOwnException() {
        val multiple = KeywordPageFilter(setOf("movie", "anime"),
            mapOf("movie" to setOf("christian"), "anime" to setOf("education")))
        assertEquals("anime", multiple.findBlock("A Christian movie and anime"))
        assertNull(multiple.findBlock("Christian movie and anime for education"))
    }

    @Test fun anyOneExceptionForTheSelectedWordIsEnough() {
        val multiple = KeywordPageFilter(setOf("movie"), mapOf("movie" to setOf("christian", "documentary")))
        assertNull(multiple.findBlock("A documentary movie"))
        assertNull(multiple.findBlock("A Christian movie"))
        assertEquals("movie", multiple.findBlock("A movie"))
    }

    @Test fun exceptionWordsUseWholeWordsAndPhrases() {
        assertEquals("movie", filter.findBlock("A movie about Christianity"))
        val phrase = KeywordPageFilter(setOf("comic book"), mapOf("comic book" to setOf("bible study")))
        assertNull(phrase.findBlock("A COMIC BOOK for Bible\n study"))
        assertEquals("comic book", phrase.findBlock("Comic book and bible studying"))
    }

    @Test fun rulesNormalizeCaseWhitespaceAndUnicode() {
        val normalized = KeywordPageFilter(setOf("MOVIE"), mapOf(" Movie " to setOf(" CHRISTIAN ")))
        assertNull(normalized.findBlock("A ＣＨＲＩＳＴＩＡＮ movie"))
        assertEquals("movie", normalized.findBlock("A MOVIE"))
    }

    @Test fun selfExceptionsAndInvalidExceptionsDoNotDisableAWord() {
        val invalid = KeywordPageFilter(setOf("movie"), mapOf("movie" to setOf("movie", "", "a|b")))
        assertEquals("movie", invalid.findBlock("A movie"))
        assertEquals("movie", invalid.findQueryBlock("movie"))
    }

    @Test fun conditionalQueriesAreDeferredToTheirActualResults() {
        val rules = mapOf("movie" to setOf("christian"))
        assertNull(GoogleSearchQuery.blockedMatch("movie", setOf("movie", "anime"), rules))
        assertEquals("movie", filter.findBlock("Movie results without the exception"))
        assertEquals("anime", GoogleSearchQuery.blockedMatch("movie anime", setOf("movie", "anime"), rules))
    }

    @Test fun excludedQueryWordsDoNotBecomePageExceptions() {
        val text = GoogleResultText(listOf("movie -christian"))
            .forMatching("movie -christian - Google Search. Movie reviews")
        assertEquals("movie", filter.findBlock(text))
    }

    @Test fun noConfiguredExceptionsKeepsExistingKeywordBehavior() {
        val plain = KeywordPageFilter(setOf("anime", "manga"))
        assertEquals("anime", plain.findBlock("ANIME and manga"))
        assertNull(plain.findBlock("animeish mangaverse"))
    }
}
