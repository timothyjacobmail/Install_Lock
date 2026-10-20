package com.example.installlock

/** One immutable input snapshot, checked on every read; no listener delay or unbounded history. */
internal class ParsedValueCache<K, V>(private val parse: (K) -> V) {
    private var cached: Pair<K, V>? = null

    @Synchronized fun get(input: K): V {
        cached?.let { if (it.first == input) return it.second }
        return parse(input).also { cached = input to it }
    }
}
