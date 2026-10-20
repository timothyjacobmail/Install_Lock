package com.example.installlock

import java.text.Normalizer
import java.util.Locale

internal class KeywordMatcher(words: Set<String>) {
    private val patterns = words.mapNotNull { normalizeWord(it) }.sorted().map { word ->
        word to Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])")
    }

    fun findMatch(text: String): String? {
        return findNormalizedMatch(normalizeText(text))
    }

    internal fun findNormalizedMatch(text: String): String? =
        patterns.firstOrNull { it.second.containsMatchIn(text) }?.first

    fun findMatches(text: String): Set<String> {
        return findNormalizedMatches(normalizeText(text))
    }

    internal fun findNormalizedMatches(text: String): Set<String> =
        patterns.mapNotNull { if (it.second.containsMatchIn(text)) it.first else null }.toSet()

    companion object {
        private val formatCharacters = Regex("\\p{Cf}")
        private val whitespace = Regex("\\s+")
        private val validWord = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]*$")
        internal fun normalizeText(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(formatCharacters, "")
                .lowercase(Locale.ROOT).replace(whitespace, " ").trim()

        fun normalizeWord(input: String): String? {
            val word = normalizeText(input)
            return word.takeIf { it.length in 1..80 &&
                validWord.matches(it) }
        }
    }
}
