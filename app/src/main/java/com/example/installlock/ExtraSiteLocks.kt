package com.example.installlock

/** Fixed removal wait, deliberately independent of the configurable protection timings. */
internal data class ExtraSiteLocks(
    val domains: Set<String> = emptySet(),
    val removals: Map<String, Long> = emptyMap()
) {
    companion object {
        const val REMOVAL_WAIT_MS = 45 * 60_000L
    }

    fun expire(now: Long): ExtraSiteLocks {
        val due = removals.filterValues { now >= it }.keys
        return copy(domains = domains - due, removals = removals.filterKeys { it in domains && it !in due })
    }

    fun add(domain: String, now: Long): ExtraSiteLocks {
        val current = expire(now)
        return current.copy(domains = current.domains + domain, removals = current.removals - domain)
    }

    fun requestRemoval(domain: String, now: Long): ExtraSiteLocks {
        val current = expire(now)
        if (domain !in current.domains || domain in current.removals) return current
        return current.copy(removals = current.removals + (domain to now + REMOVAL_WAIT_MS))
    }

    /** Chrome allows an equally specific allowlist entry to win. Remove those exceptions.
     * A parent-domain allow entry can stay: the blocked subdomain has the longer host match.
     * See https://www.chromium.org/administrators/url-blocklist-filter-format/ . */
    fun allowedPatterns(patterns: List<String>): List<String> = patterns.filterNot { pattern ->
        val host = pattern.substringBefore('/').substringBefore(':').lowercase()
        domains.any { host == it || host.endsWith(".$it") }
    }
}
