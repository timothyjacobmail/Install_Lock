package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class KeywordMatcherTest {
    @Test
    fun matchesWordsInSearchResultsIgnoringCase() {
        val matcher = KeywordMatcher(setOf("anime", "manga", "webtoon"))
        assertEquals("anime", matcher.findMatch("Search results: Best ANIME series of the year"))
        assertEquals("manga", matcher.findMatch("Read manga online"))
        assertEquals("webtoon", matcher.findMatch("Webtoon.com — comic stories"))
    }

    @Test
    fun avoidsPartialWordFalsePositives() {
        val matcher = KeywordMatcher(setOf("anime", "manga", "webtoon"))
        assertNull(matcher.findMatch("mangaverse animeish webtoons"))
        assertNull(matcher.findMatch("anime123 manga_artist"))
        assertEquals("anime", matcher.findMatch("(anime), and other results"))
    }

    @Test
    fun supportsPhrasesAndWhitespaceAcrossPageText() {
        val matcher = KeywordMatcher(setOf("comic book"))
        assertEquals("comic book", matcher.findMatch("Find COMIC\n  BOOK results"))
        assertNull(matcher.findMatch("comicbook"))
        assertNull(matcher.findMatch("comic bookshelf"))
    }

    @Test
    fun normalizesFullWidthLettersAndInvisibleFormatting() {
        val matcher = KeywordMatcher(setOf("anime"))
        assertEquals("anime", matcher.findMatch("ＡＮＩＭＥ"))
        assertEquals("anime", matcher.findMatch("an\u200bime"))
    }

    @Test
    fun validatesWordsAndEscapesPunctuation() {
        assertEquals("comic book", KeywordMatcher.normalizeWord("  Comic   BOOK  "))
        assertEquals("x-ray", KeywordMatcher.normalizeWord("X-Ray"))
        for (word in listOf("", " ", "a|b", "a.*", "x".repeat(81))) {
            assertNull(word, KeywordMatcher.normalizeWord(word))
        }
        assertEquals("x-ray", KeywordMatcher(setOf("x-ray")).findMatch("An x-ray scan"))
        assertNull(KeywordMatcher(emptySet()).findMatch("anime manga webtoon"))
    }
}
