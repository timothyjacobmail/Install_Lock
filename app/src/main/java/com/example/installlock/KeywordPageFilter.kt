package com.example.installlock

/** Generic exceptions allow the page; per-word exceptions allow only their own blocked word. */
internal class KeywordPageFilter(
    blockedWords: Set<String>,
    exceptions: Map<String, Set<String>> = emptyMap(),
    genericExceptions: Set<String> = emptySet()
) {
    private val genericWords = genericExceptions.mapNotNull { KeywordMatcher.normalizeWord(it) }.toSet()
    private val generic = KeywordMatcher(genericWords)
    private val rules = buildMap<String, Set<String>> {
        for ((input, allowed) in exceptions) {
            val word = KeywordMatcher.normalizeWord(input) ?: continue
            val normalized = allowed.mapNotNull { KeywordMatcher.normalizeWord(it) }.toSet() - word
            if (normalized.isNotEmpty()) put(word, get(word).orEmpty() + normalized)
        }
    }
    private val blocked = KeywordMatcher(blockedWords)
    private val allowed = KeywordMatcher(rules.values.flatten().toSet())
    private val unconditional = KeywordMatcher(blockedWords.filter {
        KeywordMatcher.normalizeWord(it) !in rules.keys
    }.toSet())

    fun findBlock(text: String): String? {
        if (genericWords.isNotEmpty() && generic.findMatch(text) != null) return null
        if (rules.isEmpty()) return blocked.findMatch(text)
        val hits = blocked.findMatches(text)
        if (hits.isEmpty()) return null
        val exceptionsOnPage = allowed.findMatches(text)
        return hits.firstOrNull { word -> rules[word].orEmpty().none { it in exceptionsOnPage } }
    }

    /** Conditional words need the resulting page's context; don't reject them before it loads. */
    fun findQueryBlock(positiveQuery: String): String? =
        if (genericWords.isNotEmpty()) null else unconditional.findMatch(positiveQuery)
}
