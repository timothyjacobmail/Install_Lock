package com.example.installlock

import java.text.Normalizer
import java.util.Locale

internal class KeywordMatcher(words: Set<String>) {
    private val patterns = words.mapNotNull { normalizeWord(it) }.sorted().map { word ->
        word to Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])")
    }

    fun findMatch(text: String): String? {
        val normalized = normalizeText(text)
        return patterns.firstOrNull { it.second.containsMatchIn(normalized) }?.first
    }

    companion object {
        private fun normalizeText(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(Regex("\\p{Cf}"), "")
                .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

        fun normalizeWord(input: String): String? {
            val word = normalizeText(input)
            return word.takeIf { it.length in 1..80 &&
                Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]*$").matches(it) }
        }
    }
}
