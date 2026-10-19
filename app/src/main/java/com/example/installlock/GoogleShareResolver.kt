package com.example.installlock

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Follow Google's sharing redirects without opening the destination page or saving the shared link. */
internal class GoogleShareResolver(private val fetch: (URI) -> Response = ::download) {
    data class Response(val status: Int, val location: String? = null, val html: String = "")

    fun resolve(input: String): String? {
        var current = webUri(input) ?: return null
        val visited = mutableSetOf<String>()
        val deadline = System.nanoTime() + 20_000_000_000L
        repeat(8) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return null
            if (!isWrapper(current)) return current.toASCIIString()
            if (!visited.add(current.toString())) return null
            val embedded = embeddedTarget(current)
            val target = if (embedded != null) embedded else {
                val response = try { fetch(current) } catch (_: Exception) { return null }
                when {
                    response.status in setOf(301, 302, 303, 307, 308) -> response.location
                    response.status == 200 -> metadataTarget(response.html)
                    else -> null
                }
            } ?: return null
            current = try { webUri(current.resolve(target).toString()) } catch (_: Exception) { null } ?: return null
        }
        return if (!isWrapper(current)) current.toASCIIString() else null
    }

    companion object {
        fun isWrapper(uri: URI): Boolean {
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
            return host == "share.google" || host == "search.app" ||
                    (host in setOf("google.com", "www.google.com") && uri.path in setOf("/url", "/share.google", "/search.app"))
        }

        private fun webUri(value: String): URI? {
            if (value.length > 8192) return null
            val uri = try { URI(value.trim()) } catch (_: Exception) { return null }
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.host == null || uri.userInfo != null) return null
            if (isWrapper(uri) && (!uri.scheme.equals("https", true) || uri.port !in setOf(-1, 443))) return null
            return uri
        }

        private fun embeddedTarget(uri: URI): String? {
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("google.com", "www.google.com") || uri.path != "/url") return null
            val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { item ->
                val name = item.substringBefore('=')
                if (name !in setOf("url", "q") || '=' !in item) null else try {
                    name to URLDecoder.decode(item.substringAfter('='), "UTF-8")
                } catch (_: Exception) { null }
            }.toMap()
            return listOfNotNull(parameters["url"], parameters["q"]).firstOrNull { webUri(it) != null }
        }

        private fun metadataTarget(html: String): String? {
            val tags = Regex("<(meta|link)\\b[^>]*>", RegexOption.IGNORE_CASE)
            val attribute = Regex("""([a-zA-Z:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
            val candidates = mutableListOf<Pair<Int, String>>()
            for (tag in tags.findAll(html)) {
                val attributes = attribute.findAll(tag.value).associate {
                    it.groupValues[1].lowercase(Locale.ROOT) to decodeHtml(it.groupValues[2].ifEmpty { it.groupValues[3] })
                }
                when {
                    attributes["http-equiv"].equals("refresh", true) -> {
                        val target = Regex("(?i)\\burl\\s*=\\s*(.+)").find(attributes["content"].orEmpty())
                            ?.groupValues?.get(1)?.trim()?.trim('\'', '"')
                        if (target != null) candidates.add(0 to target)
                    }
                    attributes["property"].equals("og:url", true) -> attributes["content"]?.let { candidates.add(1 to it) }
                    attributes["rel"].equals("canonical", true) -> attributes["href"]?.let { candidates.add(2 to it) }
                }
            }
            return candidates.sortedBy { it.first }.map { it.second }.firstOrNull { value ->
                val uri = webUri(value)
                uri != null && !isWrapper(uri)
            } ?: candidates.firstOrNull { it.first == 0 }?.second
        }

        private fun decodeHtml(value: String): String = value.replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match ->
                val number = match.groupValues[1]
                val code = if (number.startsWith('x')) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
                if (code != null && code in 32..126) code.toChar().toString() else match.value
            }

        private fun download(uri: URI): Response {
            // Requests are made only to recognized Google sharing/redirect endpoints.
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status != 200) return Response(status, location)
                val html = connection.inputStream.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() < 524288 && !Thread.currentThread().isInterrupted) {
                        val read = stream.read(buffer, 0, minOf(buffer.size, 524288 - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8")
                }
                return Response(status, location, html)
            } finally { connection.disconnect() }
        }
    }
}
