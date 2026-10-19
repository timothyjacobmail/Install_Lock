package com.example.installlock

import org.json.JSONObject
import java.net.URI

/** Read the listing's own structured metadata, never recommendation or description text. */
internal object PlayStoreListing {
    private val scripts = Regex("""<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun category(html: String, packageName: String): String? {
        for (script in scripts.findAll(html)) {
            val listing = try { JSONObject(script.groupValues[1]) } catch (_: Exception) { continue }
            if (listing.optString("@type") != "SoftwareApplication") continue
            val url = try { URI(listing.optString("url")) } catch (_: Exception) { continue }
            if (url.scheme != "https" || url.host != "play.google.com" ||
                !(url.path == "/store/apps/details" || url.path.startsWith("/store/apps/details/"))) continue
            val ids = url.rawQuery.orEmpty().split('&').filter { it.startsWith("id=") }.map { it.removePrefix("id=") }
            if (ids != listOf(packageName)) continue
            val category = listing.optString("applicationCategory")
            if (Regex("[A-Z][A-Z_]{1,79}").matches(category)) return category
        }
        return null
    }
}
