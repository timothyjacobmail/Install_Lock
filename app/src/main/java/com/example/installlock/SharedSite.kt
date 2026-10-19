package com.example.installlock

import java.net.URI
import java.util.Locale

internal object SharedSite {
    private val webUrl = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val domain = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    private fun uriFromText(text: String?): URI? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = webUrl.find(value)?.value ?: value
        if (candidate.length > 8192) return null
        val url = if (candidate.contains("://")) candidate else "https://$candidate"
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        return uri
    }

    private fun host(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.") ?: return null
        return host.takeIf { domain.matches(it) }
    }

    /** Host-only extraction remains available for rules that block entire domains. */
    fun domainFromText(text: String?): String? = uriFromText(text)?.let { host(it) }

    /** Narrowest path first, then parent directories and the whole domain. Never share URL tokens. */
    fun whitelistOptions(text: String?): List<String> {
        var uri = directWebsite(text) ?: uriFromText(text) ?: return emptyList()
        if (GoogleShareResolver.isWrapper(uri)) {
            // Google often includes a plain original domain beside or above its short link.
            uri = text.orEmpty().split(Regex("\\s+")).asSequence().map { it.trim('<', '>', '"', '\'', '(', ')', ',', ';').replace("\\.", ".") }
                .filter { "://" !in it && SitePattern.normalize(it) != null &&
                        it.substringBefore('/').substringBefore(':').substringAfterLast('.').any { letter -> letter.isLetter() } }
                .mapNotNull { uriFromText(it) }.firstOrNull { !GoogleShareResolver.isWrapper(it) }
                ?: return emptyList()
        }
        val host = host(uri) ?: return emptyList()
        val authority = host + if (uri.port != -1) ":${uri.port}" else ""
        // A literal '*' in a real path must not turn into a whitelist wildcard.
        val path = uri.rawPath.orEmpty().replace("*", "%2A")
        val exact = SitePattern.normalize(authority + path) ?: return emptyList()
        val options = linkedSetOf(exact)
        var slash = path.lastIndexOf('/', path.length - if (path.endsWith('/')) 2 else 1)
        while (slash > 0 && options.size < 128) {
            options.add(authority + path.substring(0, slash + 1))
            slash = path.lastIndexOf('/', slash - 1)
        }
        options.add(authority)
        return options.toList()
    }

    private fun directWebsite(text: String?): URI? = webUrl.findAll(text.orEmpty()).mapNotNull { uriFromText(it.value) }
        .firstOrNull { !GoogleShareResolver.isWrapper(it) }

    fun shortLinkFromText(text: String?): String? {
        if (directWebsite(text) != null) return null
        return (webUrl.findAll(text.orEmpty()).mapNotNull { uriFromText(it.value) }
            .firstOrNull { GoogleShareResolver.isWrapper(it) }
            ?: uriFromText(text)?.takeIf { GoogleShareResolver.isWrapper(it) })?.toASCIIString()
    }
}
