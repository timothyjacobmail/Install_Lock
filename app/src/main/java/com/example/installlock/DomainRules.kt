package com.example.installlock

import java.net.IDN
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal enum class DomainAction(val title: String) { BLOCK("Block"), ALLOW("Allow") }
internal enum class DomainMatchType(val title: String) {
    CONTAINS("Domain contains text"),
    SUFFIX("Domain or suffix, including subdomains"),
    EXACT("Exact domain")
}

internal data class DomainRule(val id: String, val action: DomainAction, val type: DomainMatchType, val value: String) {
    val description: String get() = "${action.title} · ${type.title}: $value"
    fun encode() = "$id|${action.name}|${type.name}|$value"

    companion object {
        private val validId = Regex("[A-Za-z0-9-]{1,80}")
        fun decode(raw: String): DomainRule? {
            val parts = raw.split('|')
            if (parts.size != 4 || !validId.matches(parts[0])) return null
            val action = DomainAction.entries.firstOrNull { it.name == parts[1] } ?: return null
            val type = DomainMatchType.entries.firstOrNull { it.name == parts[2] } ?: return null
            val value = DomainRules.normalizeValue(type, parts[3]) ?: return null
            return DomainRule(parts[0], action, type, value)
        }
    }
}

internal data class DomainMatch(val position: Int, val rule: DomainRule)

/** Ordered, first-match rules. An unmatched domain passes this filter; independent protections still apply. */
internal class DomainRules(private val rules: List<DomainRule>) {
    fun match(host: String): DomainMatch? {
        val normalized = normalizeHost(host) ?: return null
        for ((index, rule) in rules.withIndex()) {
            val matches = when (rule.type) {
                DomainMatchType.CONTAINS -> normalized.contains(rule.value)
                DomainMatchType.SUFFIX -> normalized == rule.value || normalized.endsWith(".${rule.value}")
                DomainMatchType.EXACT -> normalized == rule.value
            }
            if (matches) return DomainMatch(index + 1, rule)
        }
        return null
    }

    companion object {
        private val hostLabel = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
        private val containsValue = Regex("[a-z0-9][a-z0-9.-]{0,79}")
        fun normalizeHost(input: String): String? {
            val host = try { IDN.toASCII(input.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                catch (_: Exception) { return null }
            return host.takeIf { it.length in 1..253 && it.split('.').all { label ->
                label.length in 1..63 && hostLabel.matches(label)
            } }
        }

        fun normalizeValue(type: DomainMatchType, input: String): String? = when (type) {
            DomainMatchType.CONTAINS -> Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).takeIf { containsValue.matches(it) }
            DomainMatchType.SUFFIX -> normalizeHost(input.trim().removePrefix("*.").removePrefix("."))
            DomainMatchType.EXACT -> normalizeHost(input)
        }

        fun hostFromAddress(input: String): String? {
            val text = input.trim()
            if (text.isEmpty() || text.length > 8192 || text.any { it.isWhitespace() }) return null
            val uri = try { URI(if ("://" in text) text else "https://$text") } catch (_: Exception) { return null }
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
            val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
            if (':' in authority) {
                val portText = authority.substringAfter(':')
                val port = portText.toIntOrNull() ?: return null
                if (!portText.all { it in '0'..'9' } || port !in 1..65535) return null
            }
            val host = normalizeHost(authority.substringBefore(':')) ?: return null
            // An unfocused omnibox can display a search query. A lone word is ambiguous;
            // single-label intranet hosts must include their explicit http(s) scheme.
            if ("://" !in text && '.' !in host) return null
            return host
        }
    }
}

/** Recognize native browser address controls, never text or links supplied by a web page. */
internal object BrowserAddress {
    private val ids = setOf("url_bar", "url_bar_text", "url_bar_title", "address_bar", "address_bar_text",
        "location_bar_edit_text", "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view")

    fun isAddressField(browser: String, resourceId: String?): Boolean =
        resourceId?.startsWith("$browser:id/") == true && resourceId.substringAfter(":id/") in ids
}
