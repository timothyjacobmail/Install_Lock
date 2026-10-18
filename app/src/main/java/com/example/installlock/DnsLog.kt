package com.example.installlock

object DnsLog {
    private val seen = LinkedHashMap<String, Boolean>()

    @Synchronized
    fun add(name: String, blocked: Boolean) {
        seen.remove(name)
        seen[name] = blocked
        while (seen.size > 300) seen.remove(seen.keys.iterator().next())
    }

    /** Most recent first. */
    @Synchronized
    fun recent(): List<Pair<String, Boolean>> =
        seen.entries.toList().asReversed().map { it.key to it.value }
}