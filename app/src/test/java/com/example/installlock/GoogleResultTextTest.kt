package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class GoogleResultTextTest {
    private val matcher = KeywordMatcher(setOf("anime", "manga", "comic book"))
    private val cleaner = GoogleResultText(listOf("bleach -anime"))

    @Test fun queryRepeatedInPageTitleAndSearchLabelsDoesNotBlock() {
        for (value in listOf("bleach -anime", "bleach -anime - Google Search",
            "Search for BLEACH -ANIME", "Search query: bleach -anime")) {
            assertNull(value, matcher.findMatch(cleaner.forMatching(value)))
        }
    }

    @Test fun separateExclusionLabelsDoNotBlock() {
        for (value in listOf("-anime", " -ANIME ", "-\"anime\"")) {
            assertNull(value, matcher.findMatch(cleaner.forMatching(value)))
        }
        for (labels in listOf(listOf("bleach", "-anime", "Cleaning products and household bleach"),
            listOf("bleach", "-", "anime", "Cleaning products and household bleach"))) {
            val page = cleaner.forMatching(labels.joinToString(" ") { cleaner.forMatching(it) })
            assertNull(matcher.findMatch(page))
        }
    }

    @Test fun actualResultsStillBlockEvenWhenTheSameQueryIsInTheAggregateLabel() {
        for (value in listOf("bleach -anime Results: Bleach anime episodes",
            "Bleach anime episodes. Search for bleach -anime", "Bleach anime episodes")) {
            assertEquals(value, "anime", matcher.findMatch(cleaner.forMatching(value)))
        }
    }

    @Test fun snippetsDiscussingMinusSyntaxAreStillChecked() {
        assertEquals("anime", matcher.findMatch(cleaner.forMatching("A result discussing -anime syntax")))
        assertEquals("anime", matcher.findMatch(cleaner.forMatching("How to exclude anime from bleach searches")))
    }

    @Test fun anotherBlockedWordIsNotExempted() {
        assertEquals("manga", matcher.findMatch(cleaner.forMatching("bleach -anime Read manga online")))
        assertEquals("manga", matcher.findMatch(cleaner.forMatching("-manga")))
    }

    @Test fun queryWithBlockedPositiveTermsStillBlocks() {
        val positive = GoogleResultText(listOf("anime -manga"))
        assertEquals("anime", matcher.findMatch(positive.forMatching("anime -manga - Google Search")))
    }

    @Test fun phrasesUnicodeFormattingAndSplitLabelsWork() {
        val phrase = GoogleResultText(listOf("stories -\"comic book\""))
        assertNull(matcher.findMatch(phrase.forMatching("STORIES −“COMIC BOOK” - Google Search")))
        assertNull(matcher.findMatch(phrase.forMatching("−“comic book”")))
        assertNull(matcher.findMatch(phrase.forMatching("stories - \" comic book \" - Google Search")))
        assertNull(matcher.findMatch(cleaner.forMatching("bleach\n -an\u200bime - Google Search")))
        assertEquals("comic book", matcher.findMatch(phrase.forMatching("stories -\"comic book\" Comic book reviews")))
    }

    @Test fun noKnownQueryOrDifferentQueryDoesNotCreateAnExemption() {
        assertEquals("anime", matcher.findMatch(GoogleResultText(emptyList()).forMatching("bleach -anime")))
        assertEquals("anime", matcher.findMatch(cleaner.forMatching("unbleach -anime")))
        assertEquals("anime", matcher.findMatch(cleaner.forMatching("bleach -anime-related")))
    }

    @Test fun capturedAndSubmittedQueriesCanBothHaveTheirLabelsCleaned() {
        val queries = listOf("bleach -anime -manga", "bleach -anime")
        for (order in listOf(queries, queries.reversed())) {
            val twoQueries = GoogleResultText(order)
            assertNull(matcher.findMatch(twoQueries.forMatching("bleach -anime -manga - Google Search")))
            assertNull(matcher.findMatch(twoQueries.forMatching("-manga")))
            assertEquals("manga", matcher.findMatch(twoQueries.forMatching("Manga results for bleach -anime -manga")))
        }
    }

    @Test fun negativeOnlyQueryDoesNotExemptOperatorsInsideResults() {
        val negativeOnly = GoogleResultText(listOf("-anime"))
        assertNull(matcher.findMatch(negativeOnly.forMatching("-anime")))
        assertEquals("anime", matcher.findMatch(negativeOnly.forMatching("An explanation of -anime syntax")))
    }
}
