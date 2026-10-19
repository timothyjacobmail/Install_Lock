package com.example.installlock

import java.util.Locale

/** Normalize the host without changing Chrome's case-sensitive path or path boundary. */
internal object SitePattern {
    private val site = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d+)?(/[^\\s|*]*)?$"
    )
    private val suffixLike = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    fun normalize(input: String): String? {
        val address = input.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        val host = address.substringBefore('/').lowercase(Locale.ROOT).removePrefix("www.")
        val path = if ('/' in address) "/" + address.substringAfter('/') else ""
        val pattern = host + if (path == "/") "" else path
        if (!site.matches(pattern)) return null
        val labels = host.substringBefore(':').split('.')
        if (labels.size == 2 && labels[0] in suffixLike && labels[1].length == 2) return null
        if (':' in host && host.substringAfter(':').toIntOrNull()?.let { it in 1..65535 } != true) return null
        return pattern
    }
}
