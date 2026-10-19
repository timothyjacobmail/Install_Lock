package com.example.installlock

/** Google exclusions apply only to query validation, never to result-content filtering. */
internal object GoogleSearchQuery {
    private data class Token(val raw: String, val excludedWord: String?)

    private fun tokens(query: String): List<Token> = buildList {
        var index = 0
        while (index < query.length) {
            while (index < query.length && query[index].isWhitespace()) index++
            val start = index
            var quoted = false
            while (index < query.length && (quoted || !query[index].isWhitespace())) {
                if (query[index] == '"') quoted = !quoted
                index++
            }
            if (index == start) break
            val raw = query.substring(start, index)
            val negative = when {
                !raw.startsWith('-') || raw.startsWith("--") -> null
                raw.startsWith("-\"") && raw.endsWith('"') && raw.count { it == '"' } == 2 ->
                    raw.substring(2, raw.length - 1)
                '"' !in raw -> raw.substring(1)
                else -> null
            }?.let { KeywordMatcher.normalizeWord(it) }
            add(Token(raw, negative))
        }
    }

    fun excluding(input: String, word: String): String? {
        val query = input.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalized = KeywordMatcher.normalizeWord(word) ?: return null
        if (tokens(query).any { it.excludedWord == normalized }) return query
        val exclusion = if (Regex("^[\\p{L}\\p{N}]+$").matches(normalized)) "-$normalized" else "-\"$normalized\""
        return "$query $exclusion"
    }

    fun positiveText(query: String): String =
        tokens(query).filter { it.excludedWord == null }.joinToString(" ") { it.raw }

    fun excludedWords(query: String): Set<String> = tokens(query).mapNotNull { it.excludedWord }.toSet()

    fun blockedMatch(
        query: String,
        blockedWords: Set<String>,
        exceptions: Map<String, Set<String>> = emptyMap(),
        genericExceptions: Set<String> = emptySet()
    ): String? = KeywordPageFilter(blockedWords, exceptions, genericExceptions).findQueryBlock(positiveText(query))
}

/** Only native Google search controls qualify; callers must exclude WebView descendants. */
internal object GoogleQueryField {
    private val queryIds = setOf("search_box", "search_box_text", "search_src_text", "search_query", "search_query_text")

    fun matches(className: String, resourceId: String?, editable: Boolean): Boolean =
        editable || className == "android.widget.EditText" ||
                (resourceId?.startsWith("com.google.android.googlequicksearchbox:id/") == true &&
                        resourceId.substringAfterLast('/') in queryIds)
}
