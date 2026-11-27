package com.example.waevirmenasistan.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object WebsiteKnowledgeFetcher {

    private const val MAX_TOTAL_CHARS = 16_000
    private const val MAX_PAGE_CHARS = 6_000
    private val KEY_INFO_PATH_REGEX = Regex(
        "/(pricing|prices|fiyat|packages|paket|faq|booking|contact|about|hakkimizda|iletisim|rezervasyon)/?$",
        RegexOption.IGNORE_CASE
    )
    private val LANG_PREFIX_REGEX = Regex("^/(tr|ru|de|es|fr|it|ar)(/|$)", RegexOption.IGNORE_CASE)

    fun fetchAndExtract(rawUrl: String): String {
        var normalized = rawUrl.trim()
        require(normalized.isNotEmpty()) { "Web sitesi adresi boş." }
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            normalized = "https://$normalized"
        }

        val (finalUrl, primaryHtml) = downloadWithRedirects(normalized, maxRedirects = 4)
        val primaryText = extractCleanText(primaryHtml, MAX_PAGE_CHARS)
        require(primaryText.length >= 20) { "Sayfadan okunabilir metin çıkarılamadı." }

        val sb = StringBuilder()
        sb.append("=== ").append(finalUrl).append(" ===\n").append(primaryText)

        // Automatically discover and cache key sub-pages on the same domain (pricing, booking, contact, faq)
        val extraUrls = extractInternalInfoUrls(finalUrl, primaryHtml, maxExtra = 3)
        for (extra in extraUrls) {
            if (sb.length >= MAX_TOTAL_CHARS - 400) break
            runCatching {
                val (resolved, subHtml) = downloadWithRedirects(extra, maxRedirects = 3)
                val subText = extractCleanText(subHtml, 4_000)
                if (subText.length >= 60) {
                    sb.append("\n\n=== ").append(resolved).append(" ===\n").append(subText)
                }
            }
        }

        val result = sb.toString().trim()
        return if (result.length > MAX_TOTAL_CHARS) result.substring(0, MAX_TOTAL_CHARS).trim() else result
    }

    fun extractInternalInfoUrls(baseUrl: String, html: String, maxExtra: Int = 3): List<String> {
        val base = runCatching { URL(baseUrl) }.getOrNull() ?: return emptyList()
        val baseNorm = baseUrl.trimEnd('/')
        val matches = Regex("href\\s*=\\s*[\"']([^\"'#]+)[\"']", RegexOption.IGNORE_CASE).findAll(html)
        val found = LinkedHashSet<String>()

        for (m in matches) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("mailto:", true) || href.startsWith("tel:", true) || href.startsWith("javascript:", true)) {
                continue
            }
            val resolved = runCatching { URL(base, href) }.getOrNull() ?: continue
            if (!resolved.host.equals(base.host, ignoreCase = true)) continue

            val path = resolved.path.orEmpty()
            if (LANG_PREFIX_REGEX.containsMatchIn(path)) continue
            if (!KEY_INFO_PATH_REGEX.containsMatchIn(path)) continue

            val cleanUrl = "${resolved.protocol}://${resolved.authority}$path".trimEnd('/')
            if (!cleanUrl.equals(baseNorm, ignoreCase = true)) {
                found.add("${resolved.protocol}://${resolved.authority}$path")
                if (found.size >= maxExtra) break
            }
        }
        return found.toList()
    }

    fun extractCleanText(html: String, limit: Int = MAX_PAGE_CHARS): String {
        if (html.isBlank()) return ""

        val mainMatch = Regex("(?is)<main\\b[^>]*>(.*?)</main>").find(html)?.groupValues?.getOrNull(1)
        val working = if (mainMatch != null && mainMatch.length > 300) mainMatch else html

        val cleaned = working
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<svg\\b[^>]*>.*?</svg>"), " ")
            .replace(Regex("(?is)<nav\\b[^>]*>.*?</nav>"), " ")
            .replace(Regex("(?is)<footer\\b[^>]*>.*?</footer>"), " ")
            .replace(Regex("(?i)</?(p|div|li|tr|h[1-6]|section|article|br)\\b[^>]*>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")

        val decoded = decodeHtmlEntities(cleaned)
        val lines = decoded
            .lines()
            .map { it.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinctConsecutive()

        val joined = lines.joinToString("\n")
        return if (joined.length > limit) joined.substring(0, limit).trim() else joined
    }

    private fun List<String>.distinctConsecutive(): List<String> {
        val out = ArrayList<String>(size)
        var prev: String? = null
        for (item in this) {
            if (item != prev) {
                out.add(item)
                prev = item
            }
        }
        return out
    }

    private fun decodeHtmlEntities(input: String): String {
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&euro;", "€")
            .replace("&pound;", "£")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")

        s = Regex("&#(\\d+);").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else " "
        }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else " "
        }
        return s
    }

    private fun downloadWithRedirects(urlStr: String, maxRedirects: Int): Pair<String, String> {
        var current = urlStr
        repeat(maxRedirects + 1) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim().orEmpty()
                    if (loc.isEmpty()) error("Yönlendirme adresi boş (HTTP $code).")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) error("Web sitesi HTTP $code hatası verdi.")
                return current to body
            } finally {
                conn.disconnect()
            }
        }
        error("Çok fazla yönlendirme nedeniyle sayfa açılamadı.")
    }
}
