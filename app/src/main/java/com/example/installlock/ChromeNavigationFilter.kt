package com.example.installlock

import java.net.URI
import java.util.Locale

internal data class ChromeNavigationConfig(
    val whitelistOn: Boolean,
    val activePatterns: List<String>,
    val extraLockedDomains: Set<String>
)

internal enum class ChromeNavigationBlock { WHITELIST, EXTRA_SITE }

/** Complements Chrome's load-time policy with checks of the current, committed native URL.
 * No URL history is retained. Paths use Chrome's case-sensitive prefix matching, including
 * a directory's trailing slash; a host entry includes its subdomains. */
internal class ChromeNavigationFilter(private val config: ChromeNavigationConfig) {
    private data class Address(val host: String, val port: Int, val path: String, val query: String?)
    private data class Allowed(val host: String, val port: Int?, val path: String, val query: String?) {
        fun matches(address: Address): Boolean =
            (address.host == host || address.host.endsWith(".$host")) &&
                (port == null || port == address.port) && address.path.startsWith(path) &&
                queryMatches(query, address.query)
    }

    private val allowed = config.activePatterns.mapNotNull { pattern ->
        val normalized = SitePattern.normalize(pattern) ?: return@mapNotNull null
        val uri = uri(normalized) ?: return@mapNotNull null
        val host = DomainRules.hostFromAddress(normalized) ?: return@mapNotNull null
        Allowed(host, uri.port.takeIf { it >= 0 }, canonicalPath(uri), uri.rawQuery?.let(::canonicalEscapes))
    }
    private val extras = config.extraLockedDomains.mapNotNull(DomainRules::normalizeHost)

    fun block(address: String): ChromeNavigationBlock? {
        val current = parse(address) ?: return null // Browser internal pages and an unreadable URL are not web addresses.
        if (extras.any { current.host == it || current.host.endsWith(".$it") }) return ChromeNavigationBlock.EXTRA_SITE
        if (config.whitelistOn && allowed.none { it.matches(current) }) return ChromeNavigationBlock.WHITELIST
        return null
    }

    companion object {
        private val escape = Regex("%[0-9a-fA-F]{2}")
        private val schemePrefix = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
        private fun canonicalEscapes(text: String) = escape.replace(text) { it.value.uppercase(Locale.ROOT) }
        private fun uri(address: String): URI? = try {
            val parsed = URI(if (schemePrefix.containsMatchIn(address)) address else "https://$address")
            if (parsed.scheme.lowercase(Locale.ROOT) in setOf("http", "https")) URI(parsed.toASCIIString()) else null
        } catch (_: Exception) { null }

        private fun canonicalPath(uri: URI): String {
            // Browsers resolve dot segments before showing a route. Encoded dots must not
            // let an allowed directory prefix conceal a destination outside that directory.
            val segments = uri.rawPath.orEmpty().ifEmpty { "/" }.removePrefix("/").split('/')
            val path = mutableListOf<String>()
            for ((index, segment) in segments.withIndex()) {
                val dots = segment.replace("%2e", ".", ignoreCase = true)
                when (dots) {
                    "." -> if (index == segments.lastIndex) path.add("")
                    ".." -> {
                        if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                        if (index == segments.lastIndex) path.add("")
                    }
                    else -> path.add(segment)
                }
            }
            return canonicalEscapes("/" + path.joinToString("/"))
        }

        private fun parse(address: String): Address? {
            val host = DomainRules.hostFromAddress(address) ?: return null
            val uri = uri(address.trim()) ?: return null
            return Address(host, uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443,
                canonicalPath(uri), uri.rawQuery?.let(::canonicalEscapes))
        }

        private fun queryMatches(required: String?, actual: String?): Boolean {
            if (required.isNullOrEmpty()) return true
            val tokens = actual.orEmpty().split('&')
            // An allowlisted query cannot be bypassed by adding another value for its key.
            // Other query keys and the URL fragment do not change the allowed path.
            return required.split('&').all { expected ->
                val sameKey = tokens.filter { it.substringBefore('=') == expected.substringBefore('=') }
                sameKey.isNotEmpty() && ('=' !in expected || sameKey.all { it == expected })
            }
        }
    }
}
