package com.example.installlock

/** Remove exclusion syntax only where Google echoes a known query or its operator label.
 * Result snippets keep the normal matcher, including snippets discussing minus syntax. */
internal class GoogleResultText(queries: List<String>) {
    private data class Echo(val pattern: Regex, val positiveText: String)

    private fun normalized(value: String): String = KeywordMatcher.normalizeText(value)
        .replace('\u2212', '-').replace('\u201c', '"').replace('\u201d', '"')

    private fun echoPattern(query: String): Regex {
        val pattern = buildString {
            append("(?<![\\p{L}\\p{N}_-])")
            query.forEachIndexed { index, character ->
                when (character) {
                    ' ' -> append("\\s+")
                    '"' -> append("\\s*\"\\s*")
                    // Joining accessibility nodes inserts whitespace even when the
                    // original query has no space between the minus and its operand.
                    '-' -> if (index == 0 || query[index - 1] == ' ') append("-\\s*") else append('-')
                    else -> append(Regex.escape(character.toString()))
                }
            }
            append("(?![\\p{L}\\p{N}_-])")
        }
        return Regex(pattern)
    }

    private val knownQueries = queries.map(::normalized).filter { it.isNotEmpty() }.distinct()
        .filter { GoogleSearchQuery.excludedWords(it).isNotEmpty() }
        .sortedByDescending { it.length }
    private val echoes = knownQueries.filter { GoogleSearchQuery.positiveText(it).isNotBlank() }.map { query ->
        Echo(echoPattern(query),
            GoogleSearchQuery.positiveText(query))
    }
    private val operatorLabels = knownQueries.flatMap { query ->
        GoogleSearchQuery.excludedWords(query).flatMap { word ->
            if (' ' in word) listOf("-\"$word\"") else listOf("-$word", "-\"$word\"")
        }
    }.toSet()

    fun forMatching(value: String): String {
        var text = normalized(value)
        // Google can expose the exclusion as its own accessibility label, separately
        // from the rest of the query. This doesn't exempt the word in result sentences.
        if (text in operatorLabels) return ""
        for (echo in echoes) text = echo.pattern.replace(text) { echo.positiveText }
        return text
    }
}
