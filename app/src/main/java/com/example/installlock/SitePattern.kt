package com.example.installlock

import java.util.Locale

/** Normalize the host without changing Chrome's case-sensitive path or path boundary. */
internal object SitePattern {
    private val site = Regex(
        "^(?:localhost|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)(:\\d+)?(/[^\\s|*]*)?$"
    )
    private val suffixLike = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    fun isLocalHost(host: String) = host == "localhost" || host == "127.0.0.1"

    fun normalize(input: String): String? = normalize(input, stripLocalQuery = false)

    /** New user entries omit temporary local sign-in values; saved rule matching stays exact. */
    fun normalizeForEntry(input: String): String? = normalize(input, stripLocalQuery = true)

    private fun normalize(input: String, stripLocalQuery: Boolean): String? {
        val address = input.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        val authority = address.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.lowercase(Locale.ROOT).removePrefix("www.")
        val suppliedPath = address.substring(authority.length)
        // Local sign-in callbacks contain one-use codes and state. Authorize their stable
        // endpoint without persisting those values or requiring a new rule each login.
        val path = if (stripLocalQuery && isLocalHost(host.substringBefore(':')))
            suppliedPath.substringBefore('?').substringBefore('#') else suppliedPath
        val pattern = host + if (path == "/") "" else path
        if (!site.matches(pattern)) return null
        val labels = host.substringBefore(':').split('.')
        if (labels.size == 2 && labels[0] in suffixLike && labels[1].length == 2) return null
        if (':' in host && host.substringAfter(':').toIntOrNull()?.let { it in 1..65535 } != true) return null
        return pattern
    }
}
