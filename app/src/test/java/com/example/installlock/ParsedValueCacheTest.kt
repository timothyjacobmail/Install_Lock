package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ParsedValueCacheTest {
    @Test fun equalSnapshotsReuseParsingButEveryChangedValueIsVisibleImmediately() {
        var parses = 0
        val cache = ParsedValueCache<String, List<String>> { parses++; it.split('|') }
        val first = cache.get("block|manga")
        repeat(1000) { assertSame(first, cache.get(String("block|manga".toCharArray()))) }
        assertEquals(1, parses)
        assertEquals(listOf("allow", "org"), cache.get("allow|org"))
        assertEquals(listOf("block", "manga"), cache.get("block|manga"))
        assertEquals(3, parses)
    }

    @Test fun nullResultsAreCachedAndParsingErrorsDoNotPoisonThePreviousValue() {
        var parses = 0
        val cache = ParsedValueCache<String, Int?> { parses++; if (it == "error") error("bad input") else it.toIntOrNull() }
        assertNull(cache.get("missing"))
        assertNull(cache.get("missing"))
        assertEquals(1, parses)
        try { cache.get("error"); fail() } catch (_: IllegalStateException) { }
        assertNull(cache.get("missing"))
        assertEquals(2, parses)
        assertEquals(42, cache.get("42"))
    }

    @Test fun concurrentReadersAlwaysReceiveTheirOwnSnapshot() {
        val cache = ParsedValueCache<Int, Int> { it * 7 }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..15).map { n -> pool.submit<Boolean> {
                repeat(1000) { assertEquals(n * 7, cache.get(n)) }; true
            } }
            jobs.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }
}
