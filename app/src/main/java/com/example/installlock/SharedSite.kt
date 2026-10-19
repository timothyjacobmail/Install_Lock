package com.example.installlock

import java.net.URI
import java.util.Locale

internal object SharedSite {
    private val webUrl = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val domain = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /** Extract only the shared URL's host, never its path, query, or fragment. */
    fun domainFromText(text: String?): String? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = webUrl.find(value)?.value ?: value
        val url = if (candidate.contains("://")) candidate else "https://$candidate"
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.") ?: return null
        return host.takeIf { domain.matches(it) }
    }
}
